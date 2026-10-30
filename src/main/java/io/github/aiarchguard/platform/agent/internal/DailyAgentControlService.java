package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import io.github.aiarchguard.platform.agent.internal.DailyAgentStore.Grant;
import io.github.aiarchguard.platform.agent.internal.DailyAgentStore.Risk;
import io.github.aiarchguard.platform.agent.internal.DailyAgentStore.Settings;
import io.github.aiarchguard.platform.agentcredential.CredentialOperations;
import io.github.aiarchguard.platform.audit.*;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 日常风险/许可控制的应用事务；仅元数据，不注册生产运行时，也不创建请求或花费。 */
public class DailyAgentControlService {
    private static final long RESERVATION = 316373, PER_REQUEST = 350000, MAX_AGE = 604800;
    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final CredentialOperations credentials;
    private final DailyRuntimePolicy policy;
    private final DailyAgentStore store;
    private final AgentSchemaAvailability schema;
    private final AuditRecorder audit;
    private final TraceIdProvider traces;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final long projectLimit, deploymentLimit;

    DailyAgentControlService(ProjectAuthorization projects, CurrentActorProvider actors, CredentialOperations credentials,
            DailyRuntimePolicy policy, DailyAgentStore store, AgentSchemaAvailability schema, AuditRecorder audit,
            TraceIdProvider traces, Clock clock, ObjectMapper mapper, Environment environment, long projectLimit, long deploymentLimit) {
        this.projects = projects; this.actors = actors; this.credentials = credentials; this.policy = policy; this.store = store;
        this.schema = schema; this.audit = audit; this.traces = traces; this.clock = clock; this.mapper = mapper; this.environment = environment;
        this.projectLimit = projectLimit; this.deploymentLimit = deploymentLimit;
    }
    /** 写适配器读取正文前的独立授权边界；复用现有 owner/Origin/传输防护。 */
    public void authorizeControlWrite(UUID project, String origin, boolean secure) { access(project, true); credentials.authorize(origin, secure, true); }
    @Transactional(readOnly = true) public DailyContext context(UUID project) { access(project, false); return contextView(project); }
    @Transactional(readOnly = true) public DailySettings settings(UUID project) { access(project, false); return contextView(project).settings(); }

    /** 设置→相关授权用量；持锁比较修订，同值写入也只递增一次，成功审计与设置同事务提交。 */
    @Transactional public DailySettings updateSettings(UUID project, long revision, DailySettingsInput input) {
        access(project, true);
        if (revision < 0 || input == null || !"0.1.0".equals(input.schemaVersion()) || input.enabled() == null
                || (input.enabled() && input.grantId() == null) || (!input.enabled() && input.grantId() != null)) throw new AgentInvalidException();
        var current = store.lockSettings(project); access(project, true);
        if (current.revision() != revision) throw new AgentSettingsPreconditionException(false);
        var locks = new TreeSet<UUID>();
        if (current.grantId() != null) locks.add(current.grantId());
        Grant selected = input.enabled() ? grant(project, input.grantId()) : null;
        if (selected != null) locks.add(selected.id());
        for (UUID id : locks) store.lockUsage(project, id);
        access(project, true);
        if (input.enabled()) {
            selected = grant(project, input.grantId()); var risk = risk(project, selected.riskAcknowledgementId());
            policy.requireConfigured(); var binding = policy.binding();
            requireUsable(new Settings(true, current.revision(), selected.id()), selected, risk, binding, clock.instant());
        }
        store.updateSettings(project, input.enabled(), input.grantId());
        record(project, "agent.daily.settings.updated", Map.of("enabled", input.enabled(), "revision", current.revision() + 1));
        return contextView(project).settings();
    }
    /** 历史先按原始输入重放；只有确实创建新确认才重新要求 Maintainer 和当前事实。 */
    @Transactional public Creation<DailyRiskView> acknowledge(UUID project, String key, DailyRiskInput input) {
        access(project, false); key(key); if (input == null) throw new AgentInvalidException();
        String digest = riskDigest(input); UUID owner = configuredOwner();
        var previous = store.findRiskByKey(project, owner, key);
        if (previous.isPresent()) return replayRisk(previous.get(), digest);
        access(project, true); store.lockSettings(project); access(project, true);
        previous = store.findRiskByKey(project, owner, key);
        if (previous.isPresent()) return replayRisk(previous.get(), digest);
        validateRisk(input);
        var binding = policy.binding(); String bindingDigest = DailyRuntimePolicy.digest(binding);
        if (!bindingDigest.equals(input.runtimeBindingDigest())) throw invalidState();
        Instant now = clock.instant(); expiry(input.expiresAt(), now, binding);
        var value = new Risk(UUID.randomUUID(), project, binding.deploymentId(), owner, key, digest, bindingDigest,
            binding.credentialVersion(), binding.dataPolicyVersion(), now.truncatedTo(ChronoUnit.SECONDS), input.expiresAt(), binding, input, null);
        if (!store.insertRisk(value)) return replayRisk(store.findRiskByKey(project, owner, key).orElseThrow(AgentUnavailableException::new), digest);
        record(project, "agent.daily.risk.acknowledged", Map.of("riskId", value.id(), "expiresAt", value.expiresAt().toString()));
        return new Creation<>(riskView(value), true);
    }
    @Transactional(readOnly = true) public DailyRiskView getRisk(UUID project, UUID id) { access(project, false); return riskView(risk(project, id)); }
    /** 风险撤销与批准/启用/发送校验共用设置→用量锁，追加一次并仅关闭引用该风险的选中授权。 */
    @Transactional public DailyRiskView revokeRisk(UUID project, UUID id) {
        access(project, true); var settings = store.lockSettings(project); risk(project, id);
        store.lockRiskUsage(project, id); access(project, true);
        if (store.revokeRisk(id, configuredOwner(), actionTime())) {
            if (settings.grantId() != null && id.equals(grant(project, settings.grantId()).riskAcknowledgementId())) store.updateSettings(project, false, null);
            record(project, "agent.daily.risk.revoked", Map.of("riskId", id));
        }
        return riskView(risk(project, id));
    }
    /** 许可批准只追加历史和零用量；不选中许可、不改设置修订，也不预留额度或创建请求。 */
    @Transactional public Creation<DailyGrantView> approveGrant(UUID project, String key, DailyGrantInput input) {
        access(project, false); key(key); if (input == null) throw new AgentInvalidException();
        String digest = grantDigest(input); UUID owner = configuredOwner();
        var previous = store.findGrantByKey(project, owner, key);
        if (previous.isPresent()) return replayGrant(previous.get(), digest);
        access(project, true); store.lockSettings(project); access(project, true);
        previous = store.findGrantByKey(project, owner, key);
        if (previous.isPresent()) return replayGrant(previous.get(), digest);
        validateGrant(input); var risk = risk(project, input.riskAcknowledgementId());
        if (risk.revocation() != null || !clock.instant().isBefore(risk.expiresAt())) throw invalidState();
        var binding = policy.binding(); String bindingDigest = DailyRuntimePolicy.digest(binding);
        if (!bindingDigest.equals(input.runtimeBindingDigest()) || !matches(risk, binding, bindingDigest)) throw invalidState();
        Instant now = clock.instant(); expiry(input.expiresAt(), now, binding);
        if (input.expiresAt().isAfter(risk.expiresAt())) throw new AgentInvalidException();
        // 元数据等待后再看同一风险的时间；不能用开始时间延长已经过期的许可。
        if (!now.isBefore(risk.expiresAt())) throw invalidState();
        var value = new Grant(UUID.randomUUID(), project, binding.deploymentId(), owner, risk.id(), key, digest, bindingDigest,
            binding.credentialVersion(), input.allowedPurposes().stream().sorted().toList(), input.maxAttempts(), input.maxCostMicrousd(),
            now.truncatedTo(ChronoUnit.SECONDS), input.expiresAt(), null, new DailyAgentStore.Usage(0, 0, 0, null));
        if (!store.insertGrant(value)) return replayGrant(store.findGrantByKey(project, owner, key).orElseThrow(AgentUnavailableException::new), digest);
        store.initializeUsage(project, value.id());
        record(project, "agent.daily.grant.approved", Map.of("grantId", value.id(), "riskId", risk.id(), "expiresAt", value.expiresAt().toString()));
        return new Creation<>(grantView(value), true);
    }
    @Transactional(readOnly = true) public DailyGrantView getGrant(UUID project, UUID id) { access(project, false); return grantView(grant(project, id)); }
    @Transactional public DailyGrantView revokeGrant(UUID project, UUID id) {
        access(project, true); var settings = store.lockSettings(project); grant(project, id); store.lockUsage(project, id); access(project, true);
        if (store.revokeGrant(id, configuredOwner(), actionTime())) {
            if (id.equals(settings.grantId())) store.updateSettings(project, false, null);
            record(project, "agent.daily.grant.revoked", Map.of("grantId", id));
        }
        return grantView(grant(project, id));
    }
    /**
     * 只参与调用者已有的有界事务，绝不另开 REQUIRES_NEW；设置→授权用量按序加锁。
     * 原始剩余预算直传凭据元数据，所有等待后复核身份/时间/累计用量；不读取 Key 或发送模型。
     */
    public Grant requireCurrent(UUID project, UUID id, String purpose, LongSupplier remaining) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new AgentUnavailableException();
        remaining(remaining); access(project, false);
        DailyPurpose allowed;
        try { allowed = DailyPurpose.valueOf(purpose); } catch (RuntimeException invalid) { throw invalidState(); }
        if (allowed == DailyPurpose.PR_SUMMARY) access(project, true);
        var settings = store.lockSettings(project); remaining(remaining);
        var value = grant(project, id); store.lockUsage(project, id); remaining(remaining);
        access(project, allowed == DailyPurpose.PR_SUMMARY);
        value = grant(project, id); var risk = risk(project, value.riskAcknowledgementId());
        if (!settings.enabled() || !id.equals(settings.grantId()) || !value.allowedPurposes().contains(allowed)) throw invalidState();
        policy.requireConfigured(); var binding = policy.binding(remaining); remaining(remaining);
        access(project, allowed == DailyPurpose.PR_SUMMARY);
        requireUsable(settings, value, risk, binding, clock.instant()); remaining(remaining);
        return value;
    }
    private DailyContext contextView(UUID project) {
        var settings = store.settings(project);
        Grant grant = settings.grantId() == null ? null : grant(project, settings.grantId());
        Risk risk = grant == null ? null : risk(project, grant.riskAcknowledgementId());
        DailyBindingSnapshot binding = null; String digest = null; boolean ready = false;
        try {
            binding = policy.binding(); digest = DailyRuntimePolicy.digest(binding);
            policy.requireConfigured(); ready = true;
        } catch (AgentUnavailableException unavailable) { /* 缺失当前证据不伪造新绑定或摘要；历史仍可读。 */ }
        var reason = unavailable(settings, grant, risk, binding, ready, clock.instant());
        var view = new DailySettings(settings.enabled(), settings.revision(), settings.grantId(), reason == null, reason);
        return new DailyContext("0.1.0", binding, digest, PersonalEnablementPolicy.unknowns().stream().sorted().toList(),
            new DailyLimits(RESERVATION, PER_REQUEST, projectLimit, deploymentLimit, MAX_AGE, MAX_AGE), view,
            grant == null ? null : grantView(grant), ready, reason);
    }
    private DailyUnavailableReason unavailable(Settings settings, Grant grant, Risk risk, DailyBindingSnapshot binding, boolean ready, Instant now) {
        if (!settings.enabled()) return DailyUnavailableReason.PROJECT_DISABLED;
        if (!deploymentEnabled()) return DailyUnavailableReason.DEPLOYMENT_DISABLED;
        if (!ready || binding == null) return DailyUnavailableReason.RUNTIME_NOT_READY;
        if (risk == null) return DailyUnavailableReason.RISK_ACK_REQUIRED;
        if (risk.revocation() != null) return DailyUnavailableReason.RISK_ACK_REVOKED;
        if (!now.isBefore(risk.expiresAt())) return DailyUnavailableReason.RISK_ACK_EXPIRED;
        if (grant == null) return DailyUnavailableReason.GRANT_REQUIRED;
        if (grant.revocation() != null) return DailyUnavailableReason.GRANT_REVOKED;
        // V17 没有停止事实；不能查询或虚构 V18 stop。
        if (!now.isBefore(grant.expiresAt())) return DailyUnavailableReason.GRANT_EXPIRED;
        if (!binding.credentialVersion().equals(risk.credentialVersion()) || !binding.credentialVersion().equals(grant.credentialVersion())) return DailyUnavailableReason.CREDENTIAL_CHANGED;
        String digest = DailyRuntimePolicy.digest(binding);
        if (!matches(risk, binding, digest) || !digest.equals(grant.runtimeBindingDigest())
                || !binding.ownerId().equals(grant.ownerId()) || !binding.deploymentId().equals(grant.deploymentId())) return DailyUnavailableReason.BINDING_CHANGED;
        if (!evidenceFresh(binding, now)) return DailyUnavailableReason.EVIDENCE_EXPIRED;
        if (exhausted(grant)) return DailyUnavailableReason.GRANT_EXHAUSTED;
        return null;
    }
    private void requireUsable(Settings settings, Grant grant, Risk risk, DailyBindingSnapshot binding, Instant now) {
        if (unavailable(settings, grant, risk, binding, true, now) != null) throw invalidState();
    }
    private boolean matches(Risk risk, DailyBindingSnapshot binding, String digest) {
        return digest.equals(risk.runtimeBindingDigest()) && binding.ownerId().equals(risk.ownerId()) && binding.deploymentId().equals(risk.deploymentId())
            && binding.credentialVersion().equals(risk.credentialVersion()) && binding.dataPolicyVersion().equals(risk.dataPolicyVersion());
    }
    private boolean exhausted(Grant grant) {
        var usage = grant.usage();
        return usage.attempts() >= grant.maxAttempts() || usage.reservedMicrousd() > grant.maxCostMicrousd()
            || usage.spentMicrousd() > grant.maxCostMicrousd() - usage.reservedMicrousd()
            || grant.maxCostMicrousd() - usage.reservedMicrousd() - usage.spentMicrousd() < RESERVATION;
    }
    private DailyGrantView grantView(Grant value) {
        var usage = value.usage();
        DailyGrantState state = value.revocation() != null ? DailyGrantState.REVOKED : !clock.instant().isBefore(value.expiresAt())
            ? DailyGrantState.EXPIRED : exhausted(value) ? DailyGrantState.EXHAUSTED : DailyGrantState.ACTIVE;
        return new DailyGrantView(value.id(), value.projectId(), value.ownerId(), value.riskAcknowledgementId(), value.runtimeBindingDigest(), value.credentialVersion(),
            value.allowedPurposes(), value.maxAttempts(), value.maxCostMicrousd(), value.approvedAt(), value.expiresAt(), state,
            value.revocation() == null ? null : value.revocation().at(), null, new DailyUsage(usage.attempts(), usage.reservedMicrousd(), usage.spentMicrousd(), usage.activeRequestId()));
    }
    private DailyRiskView riskView(Risk value) {
        return new DailyRiskView(value.id(), value.projectId(), value.ownerId(), value.acknowledgedAt(), value.expiresAt(), value.runtimeBindingDigest(),
            value.credentialVersion(), value.dataPolicyVersion(), value.revocation() == null ? null : value.revocation().at());
    }
    private Risk risk(UUID project, UUID id) { if (id == null) throw new AgentNotFoundException(); return store.findRisk(project, id).orElseThrow(AgentNotFoundException::new); }
    private Grant grant(UUID project, UUID id) { if (id == null) throw new AgentNotFoundException(); return store.findGrant(project, id).orElseThrow(AgentNotFoundException::new); }
    private Creation<DailyRiskView> replayRisk(Risk previous, String digest) {
        if (!digest.equals(previous.inputDigest())) throw new AgentConflictException(); return new Creation<>(riskView(previous), false);
    }
    private Creation<DailyGrantView> replayGrant(Grant previous, String digest) {
        if (!digest.equals(previous.inputDigest())) throw new AgentConflictException(); return new Creation<>(grantView(previous), false);
    }
    private void validateRisk(DailyRiskInput input) {
        if (!"0.1.0".equals(input.schemaVersion()) || !hash(input.runtimeBindingDigest()) || !Boolean.TRUE.equals(input.sourcesReviewed())
                || !Boolean.TRUE.equals(input.riskAccepted()) || !Boolean.TRUE.equals(input.materialRightsAccepted()) || !seconds(input.expiresAt())) throw new AgentInvalidException();
    }
    private void validateGrant(DailyGrantInput input) {
        if (!"0.1.0".equals(input.schemaVersion()) || input.riskAcknowledgementId() == null || !hash(input.runtimeBindingDigest())
                || !Boolean.TRUE.equals(input.callsApproved()) || !seconds(input.expiresAt()) || input.allowedPurposes() == null || input.allowedPurposes().isEmpty()
                || input.allowedPurposes().stream().anyMatch(Objects::isNull) || new HashSet<>(input.allowedPurposes()).size() != input.allowedPurposes().size()
                || input.maxAttempts() < 1 || input.maxCostMicrousd() < RESERVATION || input.maxCostMicrousd() > (long) input.maxAttempts() * PER_REQUEST) throw new AgentInvalidException();
    }
    private void expiry(Instant expiry, Instant now, DailyBindingSnapshot binding) {
        if (!expiry.isAfter(now) || expiry.isAfter(now.plusSeconds(MAX_AGE)) || expiry.isAfter(binding.evidence().priceCatalogExpiresAt())
                || binding.evidence().sources().stream().anyMatch(source -> expiry.isAfter(source.checkedAt().plusSeconds(MAX_AGE)))) throw new AgentInvalidException();
    }
    private boolean evidenceFresh(DailyBindingSnapshot binding, Instant now) {
        return now.isBefore(binding.evidence().priceCatalogExpiresAt()) && binding.evidence().sources().stream()
            .allMatch(source -> !source.checkedAt().isAfter(now) && !source.checkedAt().isBefore(now.minusSeconds(MAX_AGE)));
    }
    /** 固定顺序摘要只含原输入；动作时间、随机 ID、当前读取状态均不进入幂等摘要。 */
    private String riskDigest(DailyRiskInput input) {
        var node = mapper.createObjectNode().put("schemaVersion", input.schemaVersion()).put("runtimeBindingDigest", input.runtimeBindingDigest())
            .put("expiresAt", instant(input.expiresAt())).put("sourcesReviewed", input.sourcesReviewed()).put("riskAccepted", input.riskAccepted()).put("materialRightsAccepted", input.materialRightsAccepted());
        return digest(node);
    }
    private String grantDigest(DailyGrantInput input) {
        var node = mapper.createObjectNode().put("schemaVersion", input.schemaVersion()).put("riskAcknowledgementId", input.riskAcknowledgementId() == null ? null : input.riskAcknowledgementId().toString()).put("runtimeBindingDigest", input.runtimeBindingDigest());
        if (input.allowedPurposes() == null) node.putNull("allowedPurposes");
        else { var purposes = node.putArray("allowedPurposes"); input.allowedPurposes().stream().sorted().forEach(value -> purposes.add(value.name())); }
        node.put("maxAttempts", input.maxAttempts()).put("maxCostMicrousd", input.maxCostMicrousd()).put("expiresAt", instant(input.expiresAt())).put("callsApproved", input.callsApproved());
        return digest(node);
    }
    private String digest(ObjectNode input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(input))); }
        catch (Exception invalid) { throw new AgentUnavailableException(); }
    }
    private String instant(Instant value) { return value == null ? null : value.toString(); }
    private boolean seconds(Instant value) { return value != null && value.getNano() == 0; }
    private boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private void key(String value) { if (value == null || !value.matches("[!-~]{1,128}")) throw new AgentInvalidException(); }
    /** 每个操作先重新检查当前 Project 权限，再检查配置 owner/deployment；不依赖旧批准的角色快照。 */
    private void access(UUID project, boolean control) {
        if (control) projects.requireMaintainer(project); else projects.requireViewer(project);
        UUID owner = configuredOwner(); configuredId("deployment-id");
        if (!owner.equals(actors.currentActor().id())) throw new ProjectPermissionDeniedException(); schema.requireReady();
    }
    private UUID configuredOwner() { return configuredId("owner-id"); }
    private UUID configuredId(String name) {
        try {
            String text = environment.getProperty("archguard.agent.credentials." + name); UUID value = UUID.fromString(text);
            if (!value.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException(); return value;
        } catch (RuntimeException invalid) { throw new AgentUnavailableException(); }
    }
    private boolean deploymentEnabled() {
        return environment.getProperty("archguard.agent.enabled", Boolean.class, false) && environment.getProperty("archguard.agent.daily.enabled", Boolean.class, false);
    }
    private Instant actionTime() { return clock.instant().truncatedTo(ChronoUnit.SECONDS); }
    private void remaining(LongSupplier remaining) {
        try { if (remaining == null || Thread.currentThread().isInterrupted() || remaining.getAsLong() <= 0) throw new AgentUnavailableException(); }
        catch (RuntimeException invalid) { throw new AgentUnavailableException(); }
    }
    private void record(UUID project, String action, Map<String, Object> metadata) { audit.record(new AuditEvent(actors.currentActor().id(), project, action, AuditResult.SUCCESS, traces.currentTraceId(), metadata)); }
    private AgentDailyStateInvalidException invalidState() { return new AgentDailyStateInvalidException(); }
}
