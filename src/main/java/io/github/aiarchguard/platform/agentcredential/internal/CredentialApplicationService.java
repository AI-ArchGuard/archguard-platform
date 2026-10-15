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

    @Override public CredentialStatus status() { owner(); return operation("STATUS", false, () -> storage().status()); }
    // Metadata only; callers enforce their own Project authorization.
    CredentialStatus internalStatus() { rejectReentry(); return storage().status(); }
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
    void internalConsume(UUID expectedVersion, LongSupplier remainingNanos, Consumer<String> finalOperation) {
        // Reject same-thread reentry before touching identity, audit, or even the deadline supplier.
        rejectReentry();
        try {
            CredentialDeadline deadline = new CredentialDeadline(remainingNanos);
            owner();
            if (expectedVersion == null || finalOperation == null) throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
            EncryptedCredentialStore currentStore = storage();
            deadline.remainingNanos();
            // Intent persistence is outside the slot lock and consumes the original execution budget.
            audit("USE_REQUESTED", AuditResult.SUCCESS, expectedVersion);
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
        rejectReentry();
        UUID owner;
        try { owner = UUID.fromString(required("owner-id")); }
        catch (RuntimeException failure) { throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
        if (!owner.equals(actors.currentActor().id())) {
            audit("AUTHORIZE", AuditResult.DENIED, null);
            throw new CredentialFailure(CredentialFailure.Kind.DENIED);
        }
    }

    private void rejectReentry() {
        if (Boolean.TRUE.equals(inCredentialUse.get())) throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
    }

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
        try {
            audits.record(new AuditEvent(actors.currentActor().id(), null, "AGENT_CREDENTIAL_" + action,
                result, traces.currentTraceId(), version == null ? Map.of("provider", "DEEPSEEK")
                : Map.of("provider", "DEEPSEEK", "credentialVersion", version.toString())));
        } catch (RuntimeException failure) { throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
    }

    private String required(String name) {
        String value = environment.getProperty("archguard.agent.credentials." + name);
        if (value == null || value.isBlank()) throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
        return value;
    }
}
