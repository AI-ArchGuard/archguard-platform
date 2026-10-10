package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class LiveAccountingService implements LiveAccountingOperations {
    private final ProjectAuthorization projects;
    private final AgentEnablementOperations controls;
    private final AgentEnablementStore enablements;
    private final CredentialVersionSource credentials;
    private final AgentStore requests;
    private final LiveAccountingStore store;
    private final SyntheticBatchInventory inventory;
    private final AgentSchemaAvailability schema;
    private final AuditRecorder audit;
    private final CurrentActorProvider actors;
    private final TraceIdProvider traces;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String deploymentId;
    private final boolean enabled;
    private final long projectLimit, deploymentLimit;

    LiveAccountingService(ProjectAuthorization projects, AgentEnablementOperations controls, AgentEnablementStore enablements,
            CredentialVersionSource credentials, AgentStore requests, LiveAccountingStore store, SyntheticBatchInventory inventory,
            AgentSchemaAvailability schema, AuditRecorder audit, CurrentActorProvider actors, TraceIdProvider traces, ObjectMapper mapper,
            Clock clock, PlatformTransactionManager transactions,
            @Value("${archguard.agent.credentials.deployment-id:}") String deploymentId,
            @Value("${archguard.agent.enabled:false}") boolean enabled,
            @Value("${archguard.agent.live-accounting.project-day-limit-microusd:5000000}") long projectLimit,
            @Value("${archguard.agent.live-accounting.deployment-day-limit-microusd:20000000}") long deploymentLimit) {
        this.projects = projects; this.controls = controls; this.enablements = enablements; this.credentials = credentials;
        this.requests = requests; this.store = store; this.inventory = inventory; this.schema = schema; this.audit = audit;
        this.actors = actors; this.traces = traces; this.mapper = mapper; this.clock = clock; this.deploymentId = deploymentId;
        this.enabled = enabled; this.projectLimit = projectLimit; this.deploymentLimit = deploymentLimit;
        transaction = new TransactionTemplate(transactions);
        // Request must already be committed; an outcome's cost persists independently of result/auth failure.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public String preview(UUID project, List<UUID> ids) {
        projects.requireViewer(project); schema.requireReady(); return LiveManifest.hash(mapper, manifest(project, ids));
    }
    @Override public LiveBatchView approve(UUID project, String origin, boolean secure, LiveBatchInput input) {
        return write(project, "agent.batch.approved", () -> {
            controls.authorizeWrite(project, origin, secure); schema.requireReady();
            Instant now = Instant.now(clock);
            if (input == null || input.enablementId() == null || input.syntheticInventoryId() == null || !input.callsApproved()
                    || input.maxRequests() < 1 || input.maxRequests() > 20 || input.maxCostMicrousd() < LiveCostPolicy.RESERVATION
                    || input.maxCostMicrousd() > input.maxRequests() * LiveCostPolicy.RESERVATION
                    || input.expiresAt() == null || !input.expiresAt().isAfter(now) || input.expiresAt().isAfter(now.plusSeconds(86400))) throw new AgentInvalidException();
            enablements.lockSettings(project);
            var approval = approval(project, input.enablementId()); validateApproval(approval);
            if (input.expiresAt().isAfter(approval.acknowledgement().expiresAt())
                    || input.expiresAt().isAfter(approval.acknowledgement().priceCatalogExpiresAt())) throw new AgentInvalidException();
            var manifest = manifest(project, input.templateRequestIds()); String hash = LiveManifest.hash(mapper, manifest);
            if (!hash.equals(input.manifestSha256())) throw new AgentInvalidException();
            var proof = inventory.resolve(project, input.syntheticInventoryId(), hash).orElseThrow(AgentUnavailableException::new);
            now = Instant.now(clock);
            if (!input.expiresAt().isAfter(now) || input.expiresAt().isAfter(proof.expiresAt())) throw new AgentInvalidException();
            validateApproval(approval);
            var view = new LiveBatchView(UUID.randomUUID(), project, input.enablementId(), actors.currentActor().id(), now,
                hash, input.expiresAt(), input.maxRequests(), input.maxCostMicrousd(), false);
            store.insert(new LiveAccountingStore.Batch(view, deployment(), input.syntheticInventoryId(), manifest, proof));
            record(project, actors.currentActor().id(), "agent.batch.approved", Map.of("batchId", view.id(), "manifestSha256", hash,
                "maxRequests", view.maxRequests(), "maxCostMicrousd", view.maxCostMicrousd(), "inventoryId", input.syntheticInventoryId(),
                "inventoryFileSha256", proof.fileSha256(), "fixtureSetVersion", proof.fixtureSetVersion(),
                "fixtureArtifactSha256", proof.fixtureArtifactSha256(), "reviewRef", proof.reviewRef())); return view;
        });
    }
    @Override public LiveBatchView get(UUID project, UUID id) {
        projects.requireViewer(project); schema.requireReady(); return batch(project, id).view();
    }
    @Override public LiveBatchView revoke(UUID project, UUID id, String origin, boolean secure) {
        return write(project, "agent.batch.revoked", () -> {
            controls.authorizeWrite(project, origin, secure); schema.requireReady();
            enablements.lockSettings(project); batch(project, id); store.lockBatch(id);
            if (store.revoke(id, actors.currentActor().id(), Instant.now(clock))) {
                record(project, actors.currentActor().id(), "agent.batch.revoked", Map.of("batchId", id));
            } return batch(project, id).view();
        });
    }
    @Override public String requestDigest(UUID project, UUID batchId, UUID templateId) {
        projects.requireViewer(project); schema.requireReady(); var batch = batch(project, batchId);
        var template = template(batch, templateId); var approval = approval(project, batch.view().enablementId());
        String prompt = "PR_SUMMARY".equals(template.selection().purpose()) ? SUMMARY_PROMPT : EXPLANATION_PROMPT;
        return AgentApplicationService.sha256(batch.view().manifestSha256() + "|" + templateId + "|" + batchId + "|"
            + approval.id() + "|" + approval.acknowledgement().credentialVersion() + "|" + prompt + "|" + MODEL_PROFILE + "|" + LiveCostPolicy.VERSION);
    }
    @Override public Admission reserve(UUID project, UUID batchId, UUID templateId, UUID requestId) {
        return write(project, "agent.live.reserved", () -> {
            projects.requireViewer(project); schema.requireReady();
            var snapshot = request(project, requestId); sameActor(snapshot);
            var settings = enablements.lockSettings(project); var batch = batch(project, batchId); store.lockBatch(batchId);
            var existing = store.attempt(project, requestId);
            if (existing.isPresent()) {
                var attempt = existing.orElseThrow();
                if (!batchId.equals(attempt.batchId()) || !templateId.equals(attempt.templateId())) throw new AgentConflictException();
                return new Admission(attempt.attemptId(), attempt.reservation(), false);
            }
            validateBatch(project, settings, batch);
            var approval = approval(project, settings.enablementId()); validateApproval(approval);
            validateRequest(snapshot, batch, templateId);
            if (projectLimit < 0 || projectLimit > 5_000_000 || deploymentLimit < 0 || deploymentLimit > 20_000_000) throw new AgentUnavailableException();
            LocalDate day = Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate();
            var batchUsage = store.lockBatch(batchId);
            var deploymentUsage = store.lockBudget("DEPLOYMENT", deployment(), day);
            var projectUsage = store.lockBudget("PROJECT", project, day);
            // Budget locks may wait while external inventory, credentials or the UTC day changes.
            projects.requireViewer(project); validateBatch(project, settings, batch); validateApproval(approval);
            if (!day.equals(Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate())) throw new AgentEnablementInvalidStateException();
            if (batchUsage.attempts() >= batch.view().maxRequests() || !fits(batchUsage, batch.view().maxCostMicrousd())
                    || !fits(deploymentUsage, deploymentLimit) || !fits(projectUsage, projectLimit)) throw new AgentQuotaExceededException();
            store.reserveBudget("DEPLOYMENT", deployment(), day, LiveCostPolicy.RESERVATION);
            store.reserveBudget("PROJECT", project, day, LiveCostPolicy.RESERVATION);
            store.reserveBatch(batchId, LiveCostPolicy.RESERVATION);
            var attempt = new LiveAccountingStore.Attempt(requestId, project, batchId, templateId, UUID.randomUUID(), deployment(), day,
                LiveCostPolicy.VERSION, LiveCostPolicy.RESERVATION, null, null);
            store.insertAttempt(attempt, approval.id(), approval.acknowledgement().credentialVersion(), settings.revision(), Instant.now(clock));
            record(project, snapshot.view().requesterId(), snapshot.view().traceId(), "agent.live.reserved", Map.of("requestId", requestId, "attemptId", attempt.attemptId(),
                "batchId", batchId, "estimatedMicrousd", LiveCostPolicy.RESERVATION, "priceVersion", LiveCostPolicy.VERSION));
            return new Admission(attempt.attemptId(), attempt.reservation(), true);
        });
    }
    @Override public Outcome settle(UUID project, UUID request, String price, LiveTokenUsage usage) { return finish(project, request, price, usage); }
    @Override public Outcome unknown(UUID project, UUID request) { return finish(project, request, null, null); }
    private Outcome finish(UUID project, UUID id, String price, LiveTokenUsage usage) {
        return write(project, "agent.live.finished", () -> {
            schema.requireReady(); var snapshot = request(project, id); sameActor(snapshot);
            enablements.lockSettings(project);
            var initial = store.attempt(project, id).orElseThrow(AgentNotFoundException::new);
            store.lockBatch(initial.batchId()); var attempt = store.attempt(project, id).orElseThrow();
            if (attempt.outcome() != null) return new Outcome(attempt.outcome(), attempt.actual());
            // Closing/revoking, removing membership or crossing midnight cannot erase an incurred charge.
            store.lockBudget("DEPLOYMENT", attempt.deploymentId(), attempt.day()); store.lockBudget("PROJECT", project, attempt.day());
            Long cost = null;
            if (attempt.priceVersion().equals(price)) {
                try { cost = LiveCostPolicy.cost(usage); } catch (AgentInvalidException invalid) { /* Untrusted accounting remains unknown. */ }
            }
            store.finish(attempt, cost == null ? null : usage, cost, Instant.now(clock));
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("requestId", id); metadata.put("attemptId", attempt.attemptId());
            metadata.put("state", cost == null ? "UNKNOWN" : "SETTLED"); metadata.put("actualMicrousd", cost == null ? "UNKNOWN" : cost);
            if (cost != null) {
                metadata.put("inputTokens", usage.inputTokens()); metadata.put("outputTokens", usage.outputTokens());
                metadata.put("cachedInputTokens", usage.cachedInputTokens()); metadata.put("priceVersion", attempt.priceVersion());
            }
            record(project, snapshot.view().requesterId(), snapshot.view().traceId(), "agent.live.finished", metadata);
            return new Outcome(cost == null ? "UNKNOWN" : "SETTLED", cost);
        });
    }
    private LiveManifest manifest(UUID project, List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(ids).size() != ids.size()) throw new AgentInvalidException();
        var templates = ids.stream().sorted().map(id -> {
            var snapshot = request(project, id);
            return new LiveManifest.Template(id, LiveManifest.selection(snapshot), LiveManifest.payload(mapper, snapshot), LiveManifest.hash(mapper, snapshot.candidates()));
        }).toList(); return new LiveManifest("0.1.0", project, templates);
    }
    private void validateRequest(AgentSnapshot snapshot, LiveAccountingStore.Batch batch, UUID templateId) {
        var expected = template(batch, templateId); var bindings = snapshot.view().bindings();
        String prompt = "PR_SUMMARY".equals(snapshot.view().purpose()) ? SUMMARY_PROMPT : EXPLANATION_PROMPT;
        if (!"RUNNING".equals(snapshot.view().state()) || !MODEL_PROFILE.equals(bindings.modelProfileVersion())
                || !"deepseek-flash".equals(bindings.modelId()) || !"responses-v1-restricted".equals(bindings.modelProtocolVersion())
                || !LiveCostPolicy.VERSION.equals(bindings.priceCatalogVersion()) || !prompt.equals(bindings.promptVersion())
                || !prompt.equals(snapshot.input().promptVersion())
                || !requestDigest(snapshot.view().projectId(), batch.view().id(), templateId).equals(bindings.inputDigest())
                || !expected.selection().equals(LiveManifest.selection(snapshot))
                || !expected.payloadSha256().equals(LiveManifest.payload(mapper, snapshot))
                || !expected.candidatesSha256().equals(LiveManifest.hash(mapper, snapshot.candidates()))) throw new AgentEnablementInvalidStateException();
    }
    private boolean fits(LiveAccountingStore.Usage value, long limit) {
        return value.reserved() >= 0 && value.spent() >= 0 && value.reserved() <= limit && value.spent() <= limit - value.reserved()
            && LiveCostPolicy.RESERVATION <= limit - value.reserved() - value.spent();
    }
    private void validateBatch(UUID project, AgentEnablementStore.Settings settings, LiveAccountingStore.Batch batch) {
        if (!enabled || !settings.enabled() || !batch.view().enablementId().equals(settings.enablementId())
                || batch.view().revoked() || !Instant.now(clock).isBefore(batch.view().expiresAt())
                || !deployment().equals(batch.deploymentId()) || batch.inventoryProof() == null
                || !inventory.resolve(project, batch.inventoryId(), batch.view().manifestSha256())
                    .filter(batch.inventoryProof()::equals).isPresent()) throw new AgentEnablementInvalidStateException();
    }
    private void validateApproval(AgentEnablementStore.Approval value) {
        var input = value.acknowledgement(); var credential = credentials.current(); Instant now = Instant.now(clock);
        if (!deployment().equals(value.deploymentId()) || value.revokedAt() != null || !now.isBefore(input.expiresAt())
                || !now.isBefore(input.priceCatalogExpiresAt()) || !LiveCostPolicy.VERSION.equals(input.priceCatalogVersion())
                || !"SYNTHETIC_ACCEPTANCE".equals(input.dataScope()) || !credential.configured()
                || !input.credentialVersion().equals(credential.credentialVersion())) throw new AgentEnablementInvalidStateException();
    }
    private LiveManifest.Template template(LiveAccountingStore.Batch batch, UUID id) {
        return batch.manifest().templates().stream().filter(value -> value.templateRequestId().equals(id)).findFirst().orElseThrow(AgentNotFoundException::new);
    }
    private AgentSnapshot request(UUID project, UUID id) { return requests.find(project, id).orElseThrow(AgentNotFoundException::new); }
    private AgentEnablementStore.Approval approval(UUID project, UUID id) { return enablements.find(project, id).orElseThrow(AgentNotFoundException::new); }
    private LiveAccountingStore.Batch batch(UUID project, UUID id) { return store.batch(project, id).orElseThrow(AgentNotFoundException::new); }
    private void sameActor(AgentSnapshot value) {
        if (!actors.currentActor().id().equals(value.view().requesterId())) throw new io.github.aiarchguard.platform.project.ProjectPermissionDeniedException();
    }
    private UUID deployment() { try { return UUID.fromString(deploymentId); } catch (RuntimeException invalid) { throw new AgentUnavailableException(); } }
    private void record(UUID project, UUID actor, String action, Map<String, Object> metadata) {
        record(project, actor, traces.currentTraceId(), action, metadata);
    }
    private void record(UUID project, UUID actor, String trace, String action, Map<String, Object> metadata) {
        audit.record(new AuditEvent(actor, project, action, AuditResult.SUCCESS, trace, metadata));
    }
    private <T> T write(UUID project, String action, Supplier<T> operation) {
        try { return transaction.execute(status -> operation.get()); }
        catch (RuntimeException failure) {
            try {
                transaction.executeWithoutResult(status -> audit.record(new AuditEvent(actors.currentActor().id(), project,
                    "agent.live.write_rejected", failure instanceof AgentQuotaExceededException || failure instanceof AgentConflictException ? AuditResult.CONFLICT
                        : failure instanceof io.github.aiarchguard.platform.project.ProjectPermissionDeniedException
                            || failure instanceof AgentEnablementInvalidStateException ? AuditResult.DENIED : AuditResult.FAILURE,
                    traces.currentTraceId(), Map.of("operation", action, "code", failure instanceof AgentQuotaExceededException ? "QUOTA_EXHAUSTED" : "DENIED_OR_UNAVAILABLE"))));
            } catch (RuntimeException unavailableAudit) { throw new AgentUnavailableException(); }
            throw failure;
        }
    }
}
