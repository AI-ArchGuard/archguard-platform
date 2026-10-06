package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.agentcredential.CredentialOperations;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AgentEnablementService implements AgentEnablementOperations {
    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final CredentialOperations credentials;
    private final CredentialVersionSource versions;
    private final AgentEnablementStore store;
    private final AgentSchemaAvailability schema;
    private final AuditRecorder audit;
    private final TraceIdProvider traces;
    private final Clock clock;
    private final String ownerId;
    private final String deploymentId;
    private final boolean deploymentEnabled;
    AgentEnablementService(ProjectAuthorization projects, CurrentActorProvider actors, CredentialOperations credentials,
            CredentialVersionSource versions, AgentEnablementStore store, AgentSchemaAvailability schema,
            AuditRecorder audit, TraceIdProvider traces, Clock clock,
            @Value("${archguard.agent.credentials.owner-id:}") String ownerId,
            @Value("${archguard.agent.credentials.deployment-id:}") String deploymentId,
            @Value("${archguard.agent.enabled:false}") boolean deploymentEnabled) {
        this.projects = projects; this.actors = actors; this.credentials = credentials; this.versions = versions;
        this.store = store; this.schema = schema; this.audit = audit; this.traces = traces; this.clock = clock;
        this.ownerId = ownerId; this.deploymentId = deploymentId; this.deploymentEnabled = deploymentEnabled;
    }
    @Override public void authorizeWrite(UUID project, String origin, boolean secure) {
        projects.requireMaintainer(project); owner(); credentials.authorize(origin, secure, true);
    }
    @Override @Transactional(readOnly = true) public AgentSettingsView settings(UUID project) {
        projects.requireViewer(project); schema.requireReady(); return view(project, store.settings(project));
    }
    @Override @Transactional public AgentSettingsView update(UUID project, long expected, AgentSettingsUpdate request) {
        projects.requireMaintainer(project); owner(); schema.requireReady();
        if (request == null || request.enabled() == null || expected < 0
                || (request.enabled() && request.enablementId() == null) || (!request.enabled() && request.enablementId() != null)) throw new AgentInvalidException();
        var current = store.lockSettings(project);
        if (current.revision() != expected) throw new AgentSettingsPreconditionException(false);
        if (request.enabled() && invalidity(approval(project, request.enablementId())) != null) throw new AgentEnablementInvalidStateException();
        store.update(project, request.enabled(), request.enablementId());
        record(project, "agent.settings.updated", Map.of("enabled", request.enabled(), "revision", current.revision() + 1));
        return view(project, store.settings(project));
    }
    @Override @Transactional public PersonalEnablementView approve(UUID project, PersonalEnablementInput input) {
        projects.requireMaintainer(project); owner(); schema.requireReady();
        Instant now = Instant.now(clock);
        PersonalEnablementPolicy.validate(input, now);
        var credential = versions.current();
        if (!credential.configured() || !input.credentialVersion().equals(credential.credentialVersion())) throw new AgentEnablementInvalidStateException();
        var approval = new AgentEnablementStore.Approval(UUID.randomUUID(), project, deployment(), actors.currentActor().id(), now, input, null);
        store.insert(approval);
        record(project, "agent.enablement.acknowledged", Map.of("enablementId", approval.id(), "credentialVersion", input.credentialVersion(), "expiresAt", input.expiresAt().toString()));
        return publicView(approval);
    }
    @Override @Transactional(readOnly = true) public PersonalEnablementView get(UUID project, UUID id) {
        projects.requireViewer(project); schema.requireReady(); return publicView(approval(project, id));
    }
    @Override @Transactional public PersonalEnablementView revoke(UUID project, UUID id) {
        projects.requireMaintainer(project); owner(); schema.requireReady();
        // Same project-settings lock order as enable/disable and, later, live-call admission.
        var settings = store.lockSettings(project);
        approval(project, id);
        if (store.revoke(id, actors.currentActor().id(), Instant.now(clock))) {
            if (id.equals(settings.enablementId())) store.update(project, false, null);
            record(project, "agent.enablement.revoked", Map.of("enablementId", id));
        }
        return publicView(approval(project, id));
    }
    private AgentSettingsView view(UUID project, AgentEnablementStore.Settings value) {
        String reason = !value.enabled() ? "PROJECT_DISABLED" : invalidity(approval(project, value.enablementId()));
        if (reason == null) reason = !deploymentEnabled ? "DEPLOYMENT_DISABLED" : "LIVE_ADAPTER_NOT_READY";
        // This slice deliberately has no model port, attempt or network admission.
        return new AgentSettingsView(value.enabled(), value.revision(), value.enablementId(), false, reason);
    }
    private String invalidity(AgentEnablementStore.Approval approval) {
        Instant now = Instant.now(clock);
        if (!approval.deploymentId().equals(deployment())) return "DEPLOYMENT_CHANGED";
        if (approval.revokedAt() != null) return "ENABLEMENT_REVOKED";
        if (!now.isBefore(approval.acknowledgement().expiresAt())) return "ENABLEMENT_EXPIRED";
        if (!now.isBefore(approval.acknowledgement().priceCatalogExpiresAt())) return "PRICE_EXPIRED";
        try {
            var current = versions.current();
            if (!current.configured() || !approval.acknowledgement().credentialVersion().equals(current.credentialVersion())) return "CREDENTIAL_CHANGED";
        } catch (CredentialFailure unavailable) { return "CREDENTIAL_UNAVAILABLE"; }
        return null;
    }
    private AgentEnablementStore.Approval approval(UUID project, UUID id) {
        return store.find(project, id).orElseThrow(AgentNotFoundException::new);
    }
    private PersonalEnablementView publicView(AgentEnablementStore.Approval value) {
        var input = value.acknowledgement();
        return new PersonalEnablementView(value.id(), value.projectId(), value.approvedBy(), value.approvedAt(), input.expiresAt(),
            input.credentialVersion(), input.modelAlias(), input.priceCatalogVersion(), input.priceCatalogExpiresAt(), input.dataScope(),
            value.revokedAt() != null, value.revokedAt());
    }
    private void owner() {
        UUID owner;
        try { owner = UUID.fromString(ownerId); } catch (RuntimeException invalid) { throw new AgentUnavailableException(); }
        if (!owner.equals(actors.currentActor().id())) throw new ProjectPermissionDeniedException();
    }
    private UUID deployment() {
        try { return UUID.fromString(deploymentId); } catch (RuntimeException invalid) { throw new AgentUnavailableException(); }
    }
    private void record(UUID project, String action, Map<String, Object> values) {
        audit.record(new AuditEvent(actors.currentActor().id(), project, action, AuditResult.SUCCESS, traces.currentTraceId(), values));
    }
}
