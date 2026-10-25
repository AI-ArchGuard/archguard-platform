package io.github.aiarchguard.platform.scanjob.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.finding.AcceptedResult;
import io.github.aiarchguard.platform.finding.InvalidResultException;
import io.github.aiarchguard.platform.finding.ResultAcceptance;
import io.github.aiarchguard.platform.repository.RepositoryCatalog;
import io.github.aiarchguard.platform.repository.RepositoryView;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import io.github.aiarchguard.platform.ruleset.RuleSetVersionView;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 扫描后台适配器：从数据库认领 attempt，将版本化请求写入文件邮箱，再收集 Runner 状态和报告。
 * 只经 Repository/RuleSet Catalog 及 ResultAcceptance 公开边界协作，不嵌入 Scanner 实现。
 * 数据库任务、文件和 Finding 接受没有整体事务；本类也不提供进程沙箱或硬取消保证。
 */
@Component
@ConditionalOnProperty(name="archguard.runner.enabled", havingValue="true", matchIfMissing=true)
final class MailboxScanJobCoordinator {
    private static final Logger LOGGER=LoggerFactory.getLogger(MailboxScanJobCoordinator.class);
    private static final long MAX_REPORT_BYTES=50L*1024*1024;
    private final ScanJobStore jobs;
    private final RepositoryCatalog repositories;
    private final RuleSetCatalog ruleSets;
    private final ResultAcceptance results;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Path requests;
    private final Path responses;
    private final Duration lease;
    private final int maxAttempts;

    MailboxScanJobCoordinator(ScanJobStore jobs,RepositoryCatalog repositories,RuleSetCatalog ruleSets,
                              ResultAcceptance results,ObjectMapper mapper,Clock clock,
                              @Value("${archguard.runner.mailbox:./runner-mailbox}") String mailbox,
                              @Value("${archguard.runner.lease:5m}") Duration lease,
                              @Value("${archguard.runner.max-attempts:2}") int maxAttempts){
        this.jobs=jobs;this.repositories=repositories;this.ruleSets=ruleSets;this.results=results;
        this.mapper=mapper;this.clock=clock;this.requests=Path.of(mailbox).toAbsolutePath().normalize().resolve("requests");
        this.responses=Path.of(mailbox).toAbsolutePath().normalize().resolve("responses");this.lease=lease;this.maxAttempts=maxAttempts;
    }

    /**
     * 每轮最多认领一个任务，先持久化新 token/租约，再写请求文件；两步之间可能发生故障。
     * 到期 RUNNING 能否重领由存储条件决定，次数耗尽与 CANCEL_REQUESTED 不由此处自动终结。
     */
    @Scheduled(fixedDelayString="${archguard.runner.dispatch-delay:1000}")
    void dispatch(){
        try{
            Files.createDirectories(requests);Files.createDirectories(responses);
            UUID token=UUID.randomUUID();Instant now=Instant.now(clock);
            jobs.claim(now,now.plus(lease),token,maxAttempts).ifPresent(job->writeRequest(job,token));
        }catch(RuntimeException|IOException e){LOGGER.error("event=scan_dispatch_failed exception={}",e.getClass().getName());}
    }

    /** 只遍历已出现的状态文件；没有响应时不主动判定超时、续租或清理耗尽任务。 */
    @Scheduled(fixedDelayString="${archguard.runner.collect-delay:1000}")
    void collect(){
        if(!Files.isDirectory(responses))return;
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(responses,"*.status.json")){
            for(Path status:stream)collect(status);
        }catch(IOException e){LOGGER.error("event=scan_collect_failed exception={}",e.getClass().getName());}
    }

    /**
     * 用已保存的项目/仓库/规则版本生成协议 0.1.0 请求，规则 YAML 仅编码为 Base64，并未加密。
     * Catalog 只核对资源归属，不执行当前用户授权；这里消费已由提交用例授权的后台任务。
     */
    private void writeRequest(ScanJobRecord job,UUID token){
        try{
            RepositoryView repository=repositories.requireRegistered(job.projectId(),job.repositoryId());
            RuleSetVersionView rules=ruleSets.requireVersion(job.projectId(),job.repositoryId(),job.ruleSetVersionId());
            Map<String,Object> request=Map.of("protocolVersion","0.1.0","jobId",job.id(),"attemptToken",token,
                "repositoryPath",repository.mountPath(),"projectIdentity",repository.scannerIdentity(),
                "rulesYamlBase64",Base64.getEncoder().encodeToString(rules.yaml().getBytes(StandardCharsets.UTF_8)),
                "scannerVersion",rules.scannerVersion());
            Path target=requests.resolve(job.id()+"-"+token+".request.json");
            atomicWrite(target,mapper.writeValueAsBytes(request));
        }catch(IOException|RuntimeException e){
            // 投递异常只尝试失败化当前 token；若此时已请求取消，存储会优先落为 CANCELLED。
            jobs.fail(job.id(),token,"runner.dispatch_failed","Runner request could not be created",null,null,Instant.now(clock));
        }
    }

    /**
     * 从文件名恢复 jobId/token，只消费当前 RUNNING 或 CANCEL_REQUESTED attempt 的响应。
     * token 防止已被重新认领的旧尝试改写任务；这里不以租约时间本身拒绝响应。
     */
    private void collect(Path status){
        String name=status.getFileName().toString();
        String base=name.substring(0,name.length()-".status.json".length());
        String[] parts=base.split("-",6);
        if(parts.length!=6){quarantine(status);return;}
        // 下方一般 I/O/运行异常只记录并隔离文件；与契约拒绝分支不同，不保证任务会进入终态。
        try{
            UUID jobId=UUID.fromString(String.join("-",parts[0],parts[1],parts[2],parts[3],parts[4]));
            UUID token=UUID.fromString(parts[5]);
            ScanJobRecord job=jobs.findById(jobId).orElse(null);
            if(job==null||job.attemptToken()==null||!job.attemptToken().equals(token)
                    ||(job.status()!=ScanJobStatus.RUNNING&&job.status()!=ScanJobStatus.CANCEL_REQUESTED)){
                // 未知任务、旧 token 或终态响应仅尝试清理文件，不重新摄取其 Finding。
                cleanup(status,responses.resolve(base+".report.json"));return;
            }
            JsonNode response=mapper.readTree(Files.readAllBytes(status));int exit=response.path("exitCode").asInt(-1);
            // 当前流程在区分退出码之前读取报告；缺文件/读取失败会走异常隔离，而非下面的正常失败分支。
            Path reportPath=responses.resolve(base+".report.json");byte[] report=readReport(reportPath);
            if(exit==0||exit==2){
                // 0/2 都是执行成功，PASS/VIOLATION 由存储按退出码区分；接受接口的版本参数固定为 0.2.1。
                RepositoryView repository=repositories.requireRegistered(job.projectId(),job.repositoryId());
                AcceptedResult accepted=results.validateAndStore(job.projectId(),job.id(),repository.scannerIdentity(),"0.2.1",report);
                // Finding 已先写入；complete 再核对 token/状态。两步没有整体事务，也未处理 false 返回值。
                // 即使任务已请求取消，此前的 Finding 摄取仍会发生；complete 只将任务落为取消并清除报告。
                jobs.complete(job.id(),token,exit,report,accepted.sha256(),false,accepted.scannerVersion(),accepted.schemaVersion(),Instant.now(clock));
            }else if(exit==70){
                // 70 的报告只经过文件大小检查，未调用 Schema/语义校验；仅作失败附件保存，不生成 Finding。
                jobs.fail(job.id(),token,"scanner.failed","Scanner failed after producing a partial report",report,sha256(report),Instant.now(clock));
            }else{
                // 其余退出码不保存所读报告；64 以输入错误标记，其他值统一记 Scanner 失败。
                jobs.fail(job.id(),token,exit==64?"scanner.invalid_input":"scanner.failed","Scanner did not produce an accepted result",null,null,Instant.now(clock));
            }
            cleanup(status,reportPath);
        }catch(InvalidResultException e){
            // 大小或契约拒绝时，按文件名尝试失败化当前 attempt，并隔离状态文件便于与正常结果分开。
            failFromName(base,"result.invalid_contract","Scanner output failed contract validation");
            cleanup(responses.resolve(base+".report.json"));quarantine(status);
        }catch(IOException|RuntimeException e){LOGGER.error("event=scan_response_failed file={} exception={}",name,e.getClass().getName());quarantine(status);}
    }

    /** 尽量从已拆出的文件名恢复标识，最终仍由存储的 token/状态条件阻止迟到结果更新。 */
    private void failFromName(String base,String code,String message){
        int split=base.length()-37;if(split<1)return;
        try{UUID job=UUID.fromString(base.substring(0,split));UUID token=UUID.fromString(base.substring(split+1));jobs.fail(job,token,code,message,null,null,Instant.now(clock));}catch(IllegalArgumentException ignored){ }
    }
    /** 先按文件大小限制为 1 字节到 50 MiB，再读取字节；这一步本身不解析或验证报告内容。 */
    private static byte[] readReport(Path path)throws IOException{long size=Files.size(path);if(size<1||size>MAX_REPORT_BYTES)throw new InvalidResultException("Report size is invalid");return Files.readAllBytes(path);}
    /** 先写同目录临时文件并请求原子移动；文件系统不支持时降级普通替换，不再保证原子可见。 */
    private static void atomicWrite(Path target,byte[] bytes)throws IOException{Path temp=target.resolveSibling(target.getFileName()+".tmp");Files.write(temp,bytes);try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}}
    private static String sha256(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    /** 尽力删除已处理文件；I/O 失败被忽略，本类没有独立清理重试器。 */
    private static void cleanup(Path...paths){for(Path path:paths)try{Files.deleteIfExists(path);}catch(IOException ignored){ }}
    /** 尽力改扩展名，使状态文件退出正常收集匹配；本方法不重试，也不保证隔离成功。 */
    private static void quarantine(Path path){try{Files.move(path,path.resolveSibling(path.getFileName()+".quarantine"),StandardCopyOption.REPLACE_EXISTING);}catch(IOException ignored){ }}
}
