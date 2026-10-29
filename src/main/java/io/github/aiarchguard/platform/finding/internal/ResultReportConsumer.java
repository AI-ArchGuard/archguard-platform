package io.github.aiarchguard.platform.finding.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.aiarchguard.platform.finding.AcceptedResult;
import io.github.aiarchguard.platform.finding.FindingView;
import io.github.aiarchguard.platform.finding.InvalidResultException;
import io.github.aiarchguard.platform.finding.ResultAcceptance;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Scanner 报告的内部消费器：固定契约校验后检查项目身份与引用闭合，再规范化并写入 Finding 模块。
 * 上游提供可信任务上下文和 Scanner 版本，本类不做用户授权、不完成任务，也不自行保存原始报告。
 */
@Service
final class ResultReportConsumer implements ResultAcceptance {
    private static final int MAX_REPORT_BYTES = 50 * 1024 * 1024;
    private static final String SCHEMA_VERSION = "0.1.0";
    private final ObjectMapper mapper;
    private final FindingIngestionStore store;
    private final Schema schema;

    /** 启动时加载随 Platform 固定的 0.1.0 Schema，缺失或初始化失败即不能提供接受能力。 */
    ResultReportConsumer(ObjectMapper mapper, FindingIngestionStore store) {
        this.mapper=mapper; this.store=store;
        SchemaRegistry registry=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.schema=registry.getSchema(readSchema());
        this.schema.initializeValidators();
    }

    /**
     * 仅在大小、Schema、项目身份、全局 ID 唯一性和引用关系全部通过后调用写入端口。
     * 返回原字节 SHA-256 和版本回执，供上游另存报告；scannerVersion 只沿用传入值。
     */
    @Override
    public AcceptedResult validateAndStore(UUID projectId, UUID jobId, String expectedIdentity,
                                           String scannerVersion, byte[] bytes) {
        if(bytes==null || bytes.length==0 || bytes.length>MAX_REPORT_BYTES) throw new InvalidResultException("Report size is invalid");
        // 先按固定 Schema 拒绝未知字段、非法枚举、路径和位置，再读取受此契约约束的业务字段。
        String json=new String(bytes,StandardCharsets.UTF_8);
        List<String> schemaErrors;
        try { schemaErrors=schema.validate(json,InputFormat.JSON).stream().map(Object::toString).sorted().toList(); }
        catch (UncheckedIOException exception) { throw new InvalidResultException("Report is not valid JSON"); }
        if(!schemaErrors.isEmpty()) throw new InvalidResultException("Report does not match Schema 0.1.0: "+schemaErrors.getFirst());
        try {
            JsonNode root=mapper.readTree(bytes);
            if(!SCHEMA_VERSION.equals(root.path("schemaVersion").asText())) throw new InvalidResultException("Result schema version is not supported");
            if(!expectedIdentity.equals(root.path("project").path("identity").asText())) throw new InvalidResultException("Result project identity does not match the repository");
            // Schema 检查形状，跨对象的 ID 唯一和引用闭合还需显式语义检查，且必须发生在任何写入之前。
            validateReferences(root);
            Map<String,UUID> evidenceIds=new HashMap<>();
            List<NormalizedEvidence> evidences=new ArrayList<>();
            // 先建立证据映射，Finding 的报告内 evidenceIds 才能安全转换为 Platform UUID。
            for(JsonNode value:root.path("evidences")){
                UUID id=stableUuid(jobId,value.path("id").asText()); evidenceIds.put(value.path("id").asText(),id);
                evidences.add(new NormalizedEvidence(id,projectId,jobId,value.path("id").asText(),value.path("kind").asText(),
                    value.path("summary").asText(),location(value.path("location"))));
            }
            List<NormalizedFinding> findings=new ArrayList<>();
            for(JsonNode value:root.path("findings")){
                List<UUID> refs=new ArrayList<>(); for(JsonNode ref:value.path("evidenceIds")) refs.add(evidenceIds.get(ref.asText()));
                JsonNode location=value.path("location");
                findings.add(new NormalizedFinding(stableUuid(jobId,value.path("id").asText()),projectId,jobId,
                    value.path("id").asText(),value.path("fingerprint").asText(),value.path("rule").path("id").asText(),
                    value.path("rule").path("version").asText(),value.path("severity").asText(),value.path("subjectId").asText(),
                    value.path("message").asText(),location.isNull()?null:location(location),List.copyOf(refs)));
            }
            // 这里只保存规范化数据；邮箱路径随后调用 ScanJob.complete 时没有统一事务，CI 导入另有外层事务。
            store.store(jobId,List.copyOf(evidences),List.copyOf(findings));
            return new AcceptedResult(sha256(bytes),scannerVersion,SCHEMA_VERSION);
        } catch(IOException exception){throw new InvalidResultException("Report is not valid JSON");}
    }

    /**
     * 检查报告所有实体共用的 ID 命名空间，并逐类验证引用目标确实存在。
     * 不只检查 Finding：依赖两端、指标作用域和其他对象归属也必须在当前报告内闭合。
     */
    private static void validateReferences(JsonNode root){
        Set<String> all=new HashSet<>(); Set<String> artifacts=ids(root,"artifacts"); Set<String> components=ids(root,"components");
        Set<String> evidences=ids(root,"evidences");
        all.add(root.path("project").path("id").asText()); all.addAll(artifacts); all.addAll(components);
        all.addAll(ids(root,"dependencies")); all.addAll(ids(root,"metrics")); all.addAll(ids(root,"findings")); all.addAll(evidences);
        // 集合会折叠同类或跨类型的重复 ID，因此与原始实体总数比较即可识别这两类冲突。
        int expected=1+root.path("artifacts").size()+root.path("components").size()+root.path("dependencies").size()
            +root.path("metrics").size()+root.path("findings").size()+root.path("evidences").size();
        if(all.size()!=expected) throw new InvalidResultException("Report identifiers must be globally unique");
        for(JsonNode v:root.path("artifacts")) require(v.path("projectId").asText(),Set.of(root.path("project").path("id").asText()));
        for(JsonNode v:root.path("components")) require(v.path("artifactId").asText(),artifacts);
        for(JsonNode v:root.path("dependencies")){require(v.path("sourceId").asText(),components);require(v.path("targetId").asText(),components);requireAll(v.path("evidenceIds"),evidences);}
        Set<String> scopes=new HashSet<>(artifacts);scopes.addAll(components);
        for(JsonNode v:root.path("metrics")) require(v.path("scopeId").asText(),scopes);
        Set<String> subjects=new HashSet<>(scopes);subjects.addAll(ids(root,"dependencies"));
        for(JsonNode v:root.path("findings")){require(v.path("subjectId").asText(),subjects);requireAll(v.path("evidenceIds"),evidences);}
    }
    /** 收集某类实体的报告内标识；重复项由 validateReferences 的总数检查统一拒绝。 */
    private static Set<String> ids(JsonNode root,String field){Set<String> result=new HashSet<>();for(JsonNode v:root.path(field))result.add(v.path("id").asText());return result;}
    private static void requireAll(JsonNode values,Set<String> valid){for(JsonNode v:values)require(v.asText(),valid);}
    private static void require(String id,Set<String> valid){if(!valid.contains(id))throw new InvalidResultException("Report contains a dangling reference");}
    /** 位置形状与数值已由 Schema 校验；可空 Finding 位置由调用处先分支处理。 */
    private static FindingView.SourceLocation location(JsonNode n){return new FindingView.SourceLocation(n.path("path").asText(),n.path("startLine").asInt(),n.path("startColumn").asInt(),n.path("endLine").asInt(),n.path("endColumn").asInt());}
    /** 同一任务与 Scanner ID 得到相同 UUID；它用于规范化主键，与报告字节摘要、跨扫描指纹不同。 */
    private static UUID stableUuid(UUID jobId,String scannerId){return UUID.nameUUIDFromBytes((jobId+"\n"+scannerId).getBytes(StandardCharsets.UTF_8));}
    /** 对收到的原始字节求摘要，不重新序列化 JSON，因而空白或字节差异也会影响摘要。 */
    private static String sha256(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    /** 只读随应用发布的契约快照，不按报告内容下载或选择其他 Schema。 */
    private static String readSchema(){try(InputStream in=ResultReportConsumer.class.getClassLoader().getResourceAsStream("contracts/scan-result-0.1.0.schema.json")){if(in==null)throw new IllegalStateException("Missing result schema snapshot");return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(IOException e){throw new IllegalStateException(e);}}
}
