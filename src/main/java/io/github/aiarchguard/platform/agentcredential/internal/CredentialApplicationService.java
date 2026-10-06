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
    private EncryptedCredentialStore store;

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
    // No actor or plaintext escapes this module boundary; callers enforce their own Project authorization.
    CredentialStatus internalStatus() { return storage().status(); }
    @Override public void rejectInvalidWrite() { owner(); audit("WRITE", AuditResult.FAILURE, null); }
    @Override public CredentialStatus write(String apiKey) {
        owner(); return operation("WRITE", true, () -> storage().write(apiKey, Instant.now(clock)));
    }
    @Override public CredentialStatus delete() { owner(); return operation("DELETE", true, () -> storage().delete()); }

    private synchronized EncryptedCredentialStore storage() {
        if (!environment.getProperty("archguard.agent.credentials.enabled", Boolean.class, false)
                || !environment.acceptsProfiles(Profiles.of("local-compose"))) {
            throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
        }
        if (store == null) {
            try { store = new EncryptedCredentialStore(Path.of(required("directory")), Path.of(required("master-file")),
                UUID.fromString(required("deployment-id"))); }
            catch (RuntimeException failure) { throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
        }
        return store;
    }

    private void owner() {
        UUID owner;
        try { owner = UUID.fromString(required("owner-id")); }
        catch (RuntimeException failure) { throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE); }
        if (!owner.equals(actors.currentActor().id())) {
            audit("AUTHORIZE", AuditResult.DENIED, null);
            throw new CredentialFailure(CredentialFailure.Kind.DENIED);
        }
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
