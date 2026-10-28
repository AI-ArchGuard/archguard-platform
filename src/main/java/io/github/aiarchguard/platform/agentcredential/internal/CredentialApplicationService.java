package io.github.aiarchguard.platform.agentcredential.internal;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.agentcredential.CredentialOperations;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/** 个人凭据应用层：核验固定 owner、默认关闭配置和 Origin，组织锁外审计及同一槽位消费。 */
@Service
final class CredentialApplicationService implements CredentialOperations {
    private final Environment environment;
    private final CurrentActorProvider actors;
    private final AuditRecorder audits;
    private final TraceIdProvider traces;
    private final Clock clock;
    private final AtomicReference<EncryptedCredentialStore> store = new AtomicReference<>();
    private final ThreadLocal<Boolean> inCredentialUse = new ThreadLocal<>();

    CredentialApplicationService(Environment environment, CurrentActorProvider actors, AuditRecorder audits,
            TraceIdProvider traces, Clock clock) {
        this.environment = environment; this.actors = actors; this.audits = audits;
        this.traces = traces; this.clock = clock;
    }

    /** 仅本地个人部署准入；Origin 校验补充浏览器边界，不能替代可信身份授权。 */
    @Override public void authorize(String origin, boolean secure, boolean mutation) {
        owner();
        try {
            if (!environment.acceptsProfiles(Profiles.of("local-compose"))
                    || !environment.getProperty("archguard.agent.credentials.enabled", Boolean.class, false)) {
                throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
            }
            URI configured = URI.create(required("origin"));
            boolean loopback = java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(configured.getHost());
            if (!configured.isAbsolute() || configured.getUserInfo() != null || configured.getQuery() != null
                    || configured.getFragment() != null || (configured.getRawPath() != null && !configured.getRawPath().isEmpty())
                    || !loopback || !java.util.Set.of("http", "https").contains(configured.getScheme())) {
                throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
            }
            boolean localHttp = "http".equals(configured.getScheme())
                && environment.getProperty("archguard.agent.credentials.allow-loopback-http", Boolean.class, false);
            if ("http".equals(configured.getScheme()) && !localHttp) throw new CredentialFailure(CredentialFailure.Kind.DENIED);
            if (!secure && !localHttp) throw new CredentialFailure(CredentialFailure.Kind.DENIED);
            if ((mutation && origin == null) || (origin != null && !origin.equals(configured.toString()))) {
                throw new CredentialFailure(CredentialFailure.Kind.DENIED);
            }
        } catch (CredentialFailure failure) {
            audit("AUTHORIZE", failure.kind() == CredentialFailure.Kind.DENIED ? AuditResult.DENIED : AuditResult.FAILURE, null);
            throw failure;
        } catch (RuntimeException failure) {
            audit("AUTHORIZE", AuditResult.FAILURE, null);
            throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
        }
    }

    /** owner 查询只返回已认证状态，不测试提供方 Key 或账户。 */
    @Override public CredentialStatus status() { owner(); return operation("STATUS", false, () -> storage().status()); }
    // Metadata only; callers enforce their own Project authorization.
    CredentialStatus internalStatus() { rejectReentry(); return storage().status(); }
    /** 内部有界元数据读取，不做 Project 授权；调用方必须另行核验业务资源。 */
    CredentialStatus internalStatus(LongSupplier remainingNanos) {
        rejectReentry();
        try {
            var deadline = new CredentialDeadline(remainingNanos);
            var currentStore = storage(); deadline.remainingNanos();
            return currentStore.status(deadline::remainingNanos);
        } catch (RuntimeException unavailable) { throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
    }
    /** 兼容消费先落意图审计再进入槽位，回调期间以线程作用域阻止跨入口重入。 */
    void internalConsume(UUID expectedVersion, Consumer<String> finalOperation) {
        owner();
        if (expectedVersion == null || finalOperation == null) throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
        EncryptedCredentialStore currentStore = storage();
        // Audit intent BEFORE acquiring the store lock. Live accounting/result audits record the outcome after release.
        audit("USE_REQUESTED", AuditResult.SUCCESS, expectedVersion);
        currentStore.consume(expectedVersion, apiKey -> {
            inCredentialUse.set(true);
            try { finalOperation.accept(apiKey); }
            finally { inCredentialUse.remove(); }
        });
    }
    /** 原执行预算覆盖身份、锁外意图审计及锁等待；回调只承担最终同步使用。 */
    void internalConsume(UUID expectedVersion, LongSupplier remainingNanos, Consumer<String> finalOperation) {
        // Reject same-thread reentry before touching identity, audit, or even the deadline supplier.
        rejectReentry();
        try {
            CredentialDeadline deadline = new CredentialDeadline(remainingNanos);
            owner(deadline::remainingNanos);
            if (expectedVersion == null || finalOperation == null) throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
            EncryptedCredentialStore currentStore = storage();
            deadline.remainingNanos();
            // Intent persistence is outside the slot lock and consumes the original execution budget.
            audit("USE_REQUESTED", AuditResult.SUCCESS, expectedVersion, deadline::remainingNanos);
            deadline.remainingNanos();
            currentStore.consume(expectedVersion, deadline::remainingNanos, apiKey -> {
                inCredentialUse.set(true);
                try { finalOperation.accept(apiKey); }
                finally { inCredentialUse.remove(); }
            });
        } catch (CredentialFailure safe) { throw new CredentialFailure(safe.kind()); }
        catch (RuntimeException unavailable) { throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
    }
    @Override public void rejectInvalidWrite() { owner(); audit("WRITE", AuditResult.FAILURE, null); }
    @Override public CredentialStatus write(String apiKey) {
        owner(); return operation("WRITE", true, () -> storage().write(apiKey, Instant.now(clock)));
    }
    @Override public CredentialStatus delete() { owner(); return operation("DELETE", true, () -> storage().delete()); }

    /** 惰性构造不读秘密文件；CAS 只返回一个缓存实例，保持全服务共用一个锁域。 */
    private EncryptedCredentialStore storage() {
        if (!environment.getProperty("archguard.agent.credentials.enabled", Boolean.class, false)
                || !environment.acceptsProfiles(Profiles.of("local-compose"))) {
            throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
        }
        EncryptedCredentialStore currentStore = store.get();
        if (currentStore == null) {
            // Construction does not read credential/master files. Only the CAS winner is ever used,
            // so concurrent initialization cannot create a second credential lock domain.
            try {
                EncryptedCredentialStore candidate = new EncryptedCredentialStore(Path.of(required("directory")),
                    Path.of(required("master-file")), UUID.fromString(required("deployment-id")));
                store.compareAndSet(null, candidate);
                currentStore = store.get();
            }
            catch (RuntimeException failure) { throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
        }
        return currentStore;
    }

    private void owner() {
        owner(null);
    }

    /** 配置 owner UUID 是独立授权条件，Project Maintainer 身份不能替代它。 */
    private void owner(LongSupplier remainingNanos) {
        rejectReentry();
        UUID owner;
        try { owner = UUID.fromString(required("owner-id")); }
        catch (RuntimeException failure) { throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
        if (!owner.equals(actors.currentActor().id())) {
            audit("AUTHORIZE", AuditResult.DENIED, null, remainingNanos);
            throw new CredentialFailure(CredentialFailure.Kind.DENIED);
        }
    }

    private void rejectReentry() {
        if (Boolean.TRUE.equals(inCredentialUse.get())) throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
    }

    /** 意图审计失败零变更；文件与审计库不共享事务，结果审计失败时文件可能已经改变。 */
    private CredentialStatus operation(String action, boolean mutation, Supplier<CredentialStatus> operation) {
        // The filesystem and audit database cannot share a transaction. Persist intent before mutation.
        if (mutation) audit(action + "_REQUESTED", AuditResult.SUCCESS, null);
        CredentialStatus status;
        try { status = operation.get(); }
        catch (CredentialFailure failure) { audit(action, AuditResult.FAILURE, null); throw failure; }
        audit(action, AuditResult.SUCCESS, status.credentialVersion());
        return status;
    }

    private void audit(String action, AuditResult result, UUID version) {
        audit(action, result, version, null);
    }

    /** 部署级事件无 projectId，只记录提供方和可用版本；审计故障统一转安全不可用。 */
    private void audit(String action, AuditResult result, UUID version, LongSupplier remainingNanos) {
        try {
            var event = new AuditEvent(actors.currentActor().id(), null, "AGENT_CREDENTIAL_" + action,
                result, traces.currentTraceId(), version == null ? Map.of("provider", "DEEPSEEK")
                : Map.of("provider", "DEEPSEEK", "credentialVersion", version.toString()));
            if (remainingNanos == null) audits.record(event);
            else audits.record(event, remainingNanos);
        } catch (RuntimeException failure) { throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
    }

    private String required(String name) {
        String value = environment.getProperty("archguard.agent.credentials." + name);
        if (value == null || value.isBlank()) throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
        return value;
    }
}
