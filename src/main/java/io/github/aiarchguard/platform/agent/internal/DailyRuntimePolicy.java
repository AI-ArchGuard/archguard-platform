package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.DailyBindingSnapshot;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.DailyEvidence;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.DailySource;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.core.env.Environment;

/**
 * 每日运行绑定只读取显式配置和已认证的凭据版本元数据，不接触 Key 或外部模型。
 * 事实绑定可在调用开关关闭时展示；技术配置检查仍不能代替后续 owner、grant、费用和资源授权。
 * 不注册第二个 AgentRuntimeAvailability，也不借用合成批次的准入。
 */
final class DailyRuntimePolicy {
    private static final String PREFIX = "archguard.agent.daily.";
    private static final String DATA_POLICY = "personal-selected-nonsensitive-0.1.0";
    private final Environment environment;
    private final PersonalRuntimeSafety safety;
    private final CredentialVersionSource credentials;
    private final Clock clock;

    /** 构造时不读取元数据，不启动连接池或网络资源。 */
    DailyRuntimePolicy(Environment environment, DataSource dataSource, CredentialVersionSource credentials, Clock clock) {
        this.environment = environment; this.safety = new PersonalRuntimeSafety(environment, dataSource);
        this.credentials = credentials; this.clock = clock;
    }
    DailyBindingSnapshot binding() { return capture(null); }

    /** 原始预算直接传给元数据端口；旧实现不支持有界读取时失败关闭，绝不无界回退。 */
    DailyBindingSnapshot binding(LongSupplier remaining) {
        if (remaining == null) throw unavailable();
        return capture(remaining);
    }
    /** 只检查技术开关；发送复核另须 binding(remaining)，避免暗中重置元数据等待预算。 */
    void requireConfigured() {
        try {
            safety.requireConfigured();
            if (!environment.getProperty(PREFIX + "enabled", Boolean.class, false)) throw unavailable();
        } catch (RuntimeException invalid) { throw unavailable(); }
    }
    private DailyBindingSnapshot capture(LongSupplier remaining) {
        try {
            checkRemaining(remaining);
            UUID owner = id("archguard.agent.credentials.owner-id");
            UUID deployment = id("archguard.agent.credentials.deployment-id");
            String revision = required(PREFIX + "runtime-revision");
            if (!revision.matches("[a-f0-9]{64}")) throw unavailable();
            String secret = reference(required(PREFIX + "secret-check-ref"));
            String network = reference(required(PREFIX + "network-check-ref"));
            DailyEvidence evidence = evidence(clock.instant());
            checkRemaining(remaining);
            // 此公开端口已在凭据模块内部执行密文部署认证；状态没有 owner/deployment 字段。
            CredentialStatus status = remaining == null ? credentials.current() : credentials.current(remaining);
            checkRemaining(remaining);
            if (status == null || !status.configured() || status.credentialVersion() == null) throw unavailable();
            var profile = AuthorizedAgentSnapshotBuilder.DAILY;
            return new DailyBindingSnapshot(deployment, owner, revision, status.credentialVersion(), profile.modelId(),
                "DeepSeek-V4.1-Flash-1017", List.of(profile.modelId()), profile.protocol(), profile.explanationPrompt(),
                profile.summaryPrompt(), "0.1.0", profile.priceVersion(), DATA_POLICY, secret, network, evidence);
        } catch (RuntimeException invalid) { throw unavailable(); }
    }
    private DailyEvidence evidence(Instant now) {
        var rows = Binder.get(environment).bind(PREFIX + "evidence.sources",
            Bindable.<List<Map<String, String>>>of(org.springframework.core.ResolvableType.forClassWithGenerics(List.class,
                org.springframework.core.ResolvableType.forClassWithGenerics(Map.class, String.class, String.class))))
            .orElseThrow(DailyRuntimePolicy::unavailable);
        if (rows.size() != PersonalEnablementPolicy.sources().size()) throw unavailable();
        var sources = new ArrayList<DailySource>();
        var kinds = new HashSet<String>();
        for (var row : rows) {
            String kind = row.get("kind"), url = row.get("url");
            if (kind == null || !PersonalEnablementPolicy.sources().containsKey(kind)
                    || !PersonalEnablementPolicy.sources().get(kind).equals(url) || !kinds.add(kind)) throw unavailable();
            String version = reference(row.get("version"));
            Instant checkedAt = Instant.parse(row.get("checked-at"));
            if (checkedAt.isAfter(now) || checkedAt.isBefore(now.minus(Duration.ofDays(7)))) throw unavailable();
            sources.add(new DailySource(kind, url, version, checkedAt));
        }
        Instant expiry = Instant.parse(required(PREFIX + "evidence.price-catalog-expires-at"));
        if (!expiry.isAfter(now)) throw unavailable();
        return new DailyEvidence(sources, expiry);
    }
    private String required(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) throw unavailable();
        return value;
    }
    private UUID id(String key) {
        String value = required(key); UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw unavailable();
        return id;
    }
    private static String reference(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") || value.startsWith("sk-")) throw unavailable();
        return value;
    }
    private static void checkRemaining(LongSupplier remaining) {
        if (remaining != null && remaining.getAsLong() <= 0) throw unavailable();
    }

    /** 对已捕获的不可变绑定计算固定顺序 JSON UTF-8 摘要；不重新读取凭据、时间或开关。 */
    static String digest(DailyBindingSnapshot b) {
        try {
            var mapper = new ObjectMapper(); var json = mapper.createObjectNode();
            json.put("deploymentId", b.deploymentId().toString()).put("ownerId", b.ownerId().toString())
                .put("runtimeRevision", b.runtimeRevision()).put("credentialVersion", b.credentialVersion().toString())
                .put("modelAlias", b.modelAlias()).put("mappingSnapshot", b.mappingSnapshot());
            b.allowedResponseModels().forEach(json.putArray("allowedResponseModels")::add);
            json.put("modelProtocolVersion", b.modelProtocolVersion()).put("explanationPromptVersion", b.explanationPromptVersion())
                .put("summaryPromptVersion", b.summaryPromptVersion()).put("outputSchemaVersion", b.outputSchemaVersion())
                .put("priceCatalogVersion", b.priceCatalogVersion()).put("dataPolicyVersion", b.dataPolicyVersion())
                .put("secretCheckRef", b.secretCheckRef()).put("networkCheckRef", b.networkCheckRef());
            var evidence = json.putObject("evidence"); var sources = evidence.putArray("sources");
            b.evidence().sources().stream().sorted(Comparator.comparing(DailySource::kind)).forEach(source ->
                sources.addObject().put("kind", source.kind()).put("url", source.url()).put("version", source.version())
                    .put("checkedAt", source.checkedAt().toString()));
            evidence.put("priceCatalogExpiresAt", b.evidence().priceCatalogExpiresAt().toString());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(json)));
        } catch (Exception invalid) { throw unavailable(); }
    }
    private static AgentUnavailableException unavailable() { return new AgentUnavailableException(); }
}
