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

    @Scheduled(fixedDelayString="${archguard.runner.dispatch-delay:1000}")
    void dispatch(){
        try{
            Files.createDirectories(requests);Files.createDirectories(responses);
            UUID token=UUID.randomUUID();Instant now=Instant.now(clock);
            jobs.claim(now,now.plus(lease),token,maxAttempts).ifPresent(job->writeRequest(job,token));
        }catch(RuntimeException|IOException e){LOGGER.error("event=scan_dispatch_failed exception={}",e.getClass().getName());}
    }

    @Scheduled(fixedDelayString="${archguard.runner.collect-delay:1000}")
    void collect(){
        if(!Files.isDirectory(responses))return;
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(responses,"*.status.json")){
            for(Path status:stream)collect(status);
        }catch(IOException e){LOGGER.error("event=scan_collect_failed exception={}",e.getClass().getName());}
    }

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
            jobs.fail(job.id(),token,"runner.dispatch_failed","Runner request could not be created",null,null,Instant.now(clock));
        }
    }

    private void collect(Path status){
        String name=status.getFileName().toString();
        String base=name.substring(0,name.length()-".status.json".length());
        String[] parts=base.split("-",6);
        if(parts.length!=6){quarantine(status);return;}
        try{
            UUID jobId=UUID.fromString(String.join("-",parts[0],parts[1],parts[2],parts[3],parts[4]));
            UUID token=UUID.fromString(parts[5]);
            ScanJobRecord job=jobs.findById(jobId).orElse(null);
            if(job==null||job.attemptToken()==null||!job.attemptToken().equals(token)
                    ||(job.status()!=ScanJobStatus.RUNNING&&job.status()!=ScanJobStatus.CANCEL_REQUESTED)){
                cleanup(status,responses.resolve(base+".report.json"));return;
            }
            JsonNode response=mapper.readTree(Files.readAllBytes(status));int exit=response.path("exitCode").asInt(-1);
            Path reportPath=responses.resolve(base+".report.json");byte[] report=readReport(reportPath);
            if(exit==0||exit==2){
                RepositoryView repository=repositories.requireRegistered(job.projectId(),job.repositoryId());
                AcceptedResult accepted=results.validateAndStore(job.projectId(),job.id(),repository.scannerIdentity(),"0.2.1",report);
                jobs.complete(job.id(),token,exit,report,accepted.sha256(),false,accepted.scannerVersion(),accepted.schemaVersion(),Instant.now(clock));
            }else if(exit==70){
                jobs.fail(job.id(),token,"scanner.failed","Scanner failed after producing a partial report",report,sha256(report),Instant.now(clock));
            }else{
                jobs.fail(job.id(),token,exit==64?"scanner.invalid_input":"scanner.failed","Scanner did not produce an accepted result",null,null,Instant.now(clock));
            }
            cleanup(status,reportPath);
        }catch(InvalidResultException e){
            failFromName(base,"result.invalid_contract","Scanner output failed contract validation");
            cleanup(responses.resolve(base+".report.json"));quarantine(status);
        }catch(IOException|RuntimeException e){LOGGER.error("event=scan_response_failed file={} exception={}",name,e.getClass().getName());quarantine(status);}
    }

    private void failFromName(String base,String code,String message){
        int split=base.length()-37;if(split<1)return;
        try{UUID job=UUID.fromString(base.substring(0,split));UUID token=UUID.fromString(base.substring(split+1));jobs.fail(job,token,code,message,null,null,Instant.now(clock));}catch(IllegalArgumentException ignored){ }
    }
    private static byte[] readReport(Path path)throws IOException{long size=Files.size(path);if(size<1||size>MAX_REPORT_BYTES)throw new InvalidResultException("Report size is invalid");return Files.readAllBytes(path);}
    private static void atomicWrite(Path target,byte[] bytes)throws IOException{Path temp=target.resolveSibling(target.getFileName()+".tmp");Files.write(temp,bytes);try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}}
    private static String sha256(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void cleanup(Path...paths){for(Path path:paths)try{Files.deleteIfExists(path);}catch(IOException ignored){ }}
    private static void quarantine(Path path){try{Files.move(path,path.resolveSibling(path.getFileName()+".quarantine"),StandardCopyOption.REPLACE_EXISTING);}catch(IOException ignored){ }}
}
