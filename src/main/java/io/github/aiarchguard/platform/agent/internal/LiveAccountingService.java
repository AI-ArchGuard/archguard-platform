package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
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
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class LiveAccountingService implements LiveAccountingOperations, LiveRequestOperations {
    private final ProjectAuthorization projects;
    private final AgentEnablementOperations controls;
    private final AgentEnablementStore enablements;
    private final CredentialVersionSource credentials;
    private final AgentStore requests;
    private final LiveRequestStore links;
    private final LiveResourceAuthorizer resources;
    private final AgentRequestRouting routing;
    private final LiveAccountingStore store;
    private final SyntheticBatchInventory inventory;
    private final AgentSchemaAvailability schema;
    private final AuditRecorder audit;
    private final CurrentActorProvider actors;
    private final TraceIdProvider traces;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final LiveDatabaseWork database;
    private final String deploymentId;
    private final boolean enabled;
    private final long projectLimit, deploymentLimit;

    LiveAccountingService(ProjectAuthorization projects, AgentEnablementOperations controls, AgentEnablementStore enablements,
            CredentialVersionSource credentials, AgentStore requests, LiveRequestStore links, LiveAccountingStore store, SyntheticBatchInventory inventory,
            ScanJobOperations jobs, FindingOperations findings, DocumentOperations documents, ReportSubmissionOperations reports,
            AgentRequestRouting routing,
            AgentSchemaAvailability schema, AuditRecorder audit, CurrentActorProvider actors, TraceIdProvider traces, ObjectMapper mapper,
            Clock clock, PlatformTransactionManager transactions, LiveDatabaseWork database,
            @Value("${archguard.agent.credentials.deployment-id:}") String deploymentId,
            @Value("${archguard.agent.enabled:false}") boolean enabled,
            @Value("${archguard.agent.live-accounting.project-day-limit-microusd:5000000}") long projectLimit,
            @Value("${archguard.agent.live-accounting.deployment-day-limit-microusd:20000000}") long deploymentLimit) {
        this.projects = projects; this.controls = controls; this.enablements = enablements; this.credentials = credentials;
        this.requests = requests; this.store = store; this.inventory = inventory; this.schema = schema; this.audit = audit;
        this.links = links; this.resources = new LiveResourceAuthorizer(projects, jobs, findings, documents, reports); this.routing = routing;
        this.actors = actors; this.traces = traces; this.mapper = mapper; this.clock = clock; this.deploymentId = deploymentId;
        this.enabled = enabled; this.projectLimit = projectLimit; this.deploymentLimit = deploymentLimit;
        this.database = database;
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
                    || input.maxRequests() < 1 || input.maxRequests() > 20
                    || input.expiresAt() == null || !input.expiresAt().isAfter(now) || input.expiresAt().isAfter(now.plusSeconds(86400))) throw new AgentInvalidException();
            enablements.lockSettings(project);
            var approval = approval(project, input.enablementId()); validateApproval(approval);
            String price = approval.acknowledgement().priceCatalogVersion();
            long reservation = LiveCostPolicy.reservation(price);
            if (LiveCostPolicy.isContext(price)) {
                if (input.maxRequests() != 2 || input.maxCostMicrousd() < 2 * reservation
                        || input.maxCostMicrousd() > LiveCostPolicy.BATCH_LIMIT
                        || reservation > LiveCostPolicy.PER_REQUEST_LIMIT) throw new AgentInvalidException();
            } else if (input.maxCostMicrousd() < reservation
                    || input.maxCostMicrousd() > input.maxRequests() * reservation) throw new AgentInvalidException();
            if (input.expiresAt().isAfter(approval.acknowledgement().expiresAt())
                    || input.expiresAt().isAfter(approval.acknowledgement().priceCatalogExpiresAt())) throw new AgentInvalidException();
            var manifest = manifest(project, input.templateRequestIds()); String hash = LiveManifest.hash(mapper, manifest);
            if (LiveCostPolicy.isContext(price) && (manifest.templates().size() != 2
                    || !manifest.templates().stream().map(t -> t.selection().purpose()).collect(java.util.stream.Collectors.toSet())
                        .equals(java.util.Set.of("FINDING_EXPLANATION", "PR_SUMMARY")))) throw new AgentInvalidException();
            if (!hash.equals(input.manifestSha256())) throw new AgentInvalidException();
            var proof = inventory.resolve(project, input.syntheticInventoryId(), hash).orElseThrow(AgentUnavailableException::new);
            now = Instant.now(clock);
            if (!input.expiresAt().isAfter(now) || input.expiresAt().isAfter(proof.expiresAt())) throw new AgentInvalidException();
            validateApproval(approval);
            var view = new LiveBatchView(UUID.randomUUID(), project, input.enablementId(), actors.currentActor().id(), now,
                hash, input.expiresAt(), input.maxRequests(), input.maxCostMicrousd(), false);
            store.insert(new LiveAccountingStore.Batch(view, deployment(), input.syntheticInventoryId(), manifest, proof, price));
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
        String price = batch.costPolicyVersion();
        return AgentApplicationService.sha256(batch.view().manifestSha256() + "|" + templateId + "|" + batchId + "|"
            + approval.id() + "|" + approval.acknowledgement().credentialVersion() + "|" + prompt + "|" + LiveCostPolicy.modelProfile(price) + "|" + price);
    }
    @Override public AgentRequestView create(UUID project, UUID batchId, UUID templateId, String key, String origin, boolean secure) {
        return write(project, "agent.live.request.created", () -> createBody(project, batchId, templateId, key, origin, secure, null));
    }
    @Override public AgentRequestView create(UUID project, UUID batchId, UUID templateId, String key, String origin, boolean secure,
            LongSupplier remainingNanos) {
        if (remainingNanos == null) throw new AgentUnavailableException();
        return boundedWrite(project, "agent.live.request.created", remainingNanos,
            () -> createBody(project, batchId, templateId, key, origin, secure, remainingNanos));
    }
    private AgentRequestView createBody(UUID project, UUID batchId, UUID templateId, String key, String origin, boolean secure,
            LongSupplier remainingNanos) {
            controls.authorizeWrite(project, origin, secure); schema.requireReady();
            if (batchId == null || templateId == null || key == null || key.isBlank() || key.length() > 128
                    || key.chars().anyMatch(c -> c < 33 || c > 126)) throw new AgentInvalidException();
            var settings = enablements.lockSettings(project);
            var batch = batch(project, batchId); store.lockBatch(batchId);
            template(batch, templateId);
            UUID actor = actors.currentActor().id();
            String digest = requestDigest(project, batchId, templateId);
            var replay = requests.findByKey(project, actor, key);
            if (replay.isPresent()) return replay(replay.orElseThrow(), batchId, templateId, digest);
            validateBatch(project, settings, batch);
            var approval = approval(project, settings.enablementId()); validateApproval(approval, remainingNanos);
            var source = request(project, templateId);
            if (!("FAILED".equals(source.view().state()) || "SUCCEEDED".equals(source.view().state()))
                    || !routing.synthetic(source)) throw new AgentEnablementInvalidStateException();
            var old = source.view().bindings(); var input = source.input();
            String prompt = "PR_SUMMARY".equals(source.view().purpose()) ? SUMMARY_PROMPT : EXPLANATION_PROMPT;
            var liveInput = new AgentModelPort.ModelInput(input.purpose(), input.schemaVersion(), prompt, input.findingRef(),
                input.ruleId(), input.ruleVersion(), input.severity(), input.message(), input.subject(), input.evidence(), input.documents(), input.findings());
            var bindings = new AgentRequestView.VersionBindings(old.scanJobId(), old.reportSha256(), old.prHeadRevisionId(),
                List.copyOf(old.findingIds()), List.copyOf(old.documentVersions()), prompt, LiveCostPolicy.modelProfile(batch.costPolicyVersion()),
                "responses-v1-restricted", "deepseek-flash", old.outputSchemaVersion(), batch.costPolicyVersion(), digest);
            Instant now = Instant.now(clock);
            var view = new AgentRequestView(UUID.randomUUID(), project, actor, traces.currentTraceId(), source.view().purpose(),
                "QUEUED", bindings, null, null, null, now, now);
            var snapshot = new AgentSnapshot(view, liveInput, List.copyOf(source.candidates()));
            validateRequest(snapshot, batch, templateId, "QUEUED"); resources.validate(snapshot);
            // Public domain checks can take time; inventory, credential metadata and expiry are fresh at commit.
            projects.requireViewer(project); validateBatch(project, settings, batch); validateApproval(approval, remainingNanos);
            if (!requests.insert(snapshot, key)) return replay(requests.findByKey(project, actor, key).orElseThrow(), batchId, templateId, digest);
            links.insert(new LiveRequestStore.Link(view.id(), project, batchId, templateId, approval.id(),
                approval.acknowledgement().credentialVersion(), settings.revision(), now));
            record(project, actor, view.traceId(), "agent.live.request.created", Map.of("requestId", view.id(),
                "batchId", batchId, "templateId", templateId, "enablementId", approval.id(),
                "credentialVersion", approval.acknowledgement().credentialVersion(), "settingsRevision", settings.revision(),
                "inputDigest", digest, "modelProfileVersion", bindings.modelProfileVersion()));
            // No afterCommit hook: dispatch, Token admission and bounded trusted identity are separate gates.
            return view;
    }
    private AgentRequestView replay(AgentSnapshot snapshot, UUID batch, UUID template, String digest) {
        var link = links.find(snapshot.view().projectId(), snapshot.view().id()).orElseThrow(AgentConflictException::new);
        if (!batch.equals(link.batchId()) || !template.equals(link.templateId())
                || !LiveCostPolicy.modelProfile(snapshot.view().bindings().priceCatalogVersion()).equals(snapshot.view().bindings().modelProfileVersion())
                || !digest.equals(snapshot.view().bindings().inputDigest())) throw new AgentConflictException();
        return snapshot.view();
    }
    @Override public Admission reserve(UUID project, UUID batchId, UUID templateId, UUID requestId) {
        return write(project, "agent.live.reserved", () -> reserveBody(project, batchId, templateId, requestId, null));
    }
    @Override public Admission reserve(UUID project, UUID batchId, UUID templateId, UUID requestId, LongSupplier remainingNanos) {
        if (remainingNanos == null) throw new AgentUnavailableException();
        return boundedWrite(project, "agent.live.reserved", remainingNanos, () -> reserveBody(project, batchId, templateId, requestId, remainingNanos));
    }
    private Admission reserveBody(UUID project, UUID batchId, UUID templateId, UUID requestId, LongSupplier remainingNanos) {
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
            long reservation = LiveCostPolicy.reservation(batch.costPolicyVersion());
            if (LiveCostPolicy.isContext(batch.costPolicyVersion())) store.requireSerialProgress(batch, requestId);
            var approval = approval(project, settings.enablementId()); validateApproval(approval, remainingNanos);
            validateRequest(snapshot, batch, templateId, "RUNNING"); validateLink(snapshot, batch, templateId, settings, approval);
            if (projectLimit < 0 || projectLimit > 5_000_000 || deploymentLimit < 0 || deploymentLimit > 20_000_000) throw new AgentUnavailableException();
            LocalDate day = Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate();
            var batchUsage = store.lockBatch(batchId);
            var deploymentUsage = store.lockBudget("DEPLOYMENT", deployment(), day);
            var projectUsage = store.lockBudget("PROJECT", project, day);
            // Budget locks may wait while external inventory, credentials or the UTC day changes.
            projects.requireViewer(project); validateBatch(project, settings, batch); validateApproval(approval, remainingNanos);
            snapshot = request(project, requestId); sameActor(snapshot);
            validateRequest(snapshot, batch, templateId, "RUNNING"); validateLink(snapshot, batch, templateId, settings, approval);
            if (LiveCostPolicy.isContext(batch.costPolicyVersion())) store.requireSerialProgress(batch, requestId);
            if (!day.equals(Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate())) throw new AgentEnablementInvalidStateException();
            if (batchUsage.attempts() >= batch.view().maxRequests() || !fits(batchUsage, batch.view().maxCostMicrousd(), reservation)
                    || !fits(deploymentUsage, deploymentLimit, reservation) || !fits(projectUsage, projectLimit, reservation)) throw new AgentQuotaExceededException();
            store.reserveBudget("DEPLOYMENT", deployment(), day, reservation);
            store.reserveBudget("PROJECT", project, day, reservation);
            store.reserveBatch(batchId, reservation);
            var attempt = new LiveAccountingStore.Attempt(requestId, project, batchId, templateId, UUID.randomUUID(), deployment(), day,
                batch.costPolicyVersion(), reservation, null, null);
            store.insertAttempt(attempt, approval.id(), approval.acknowledgement().credentialVersion(), settings.revision(), Instant.now(clock));
            record(project, snapshot.view().requesterId(), snapshot.view().traceId(), "agent.live.reserved", Map.of("requestId", requestId, "attemptId", attempt.attemptId(),
                "batchId", batchId, "estimatedMicrousd", reservation, "priceVersion", batch.costPolicyVersion()));
            return new Admission(attempt.attemptId(), attempt.reservation(), true);
    }
    @Override public SendApproval checkBeforeSend(UUID project, UUID batchId, UUID templateId, UUID requestId) {
        return write(project, "agent.live.send_check", () -> checkBeforeSendBody(project, batchId, templateId, requestId, null));
    }
    @Override public SendApproval checkBeforeSend(UUID project, UUID batchId, UUID templateId, UUID requestId, LongSupplier remainingNanos) {
        if (remainingNanos == null) throw new AgentUnavailableException();
        return boundedWrite(project, "agent.live.send_check", remainingNanos, () -> checkBeforeSendBody(project, batchId, templateId, requestId, remainingNanos));
    }
    private SendApproval checkBeforeSendBody(UUID project, UUID batchId, UUID templateId, UUID requestId, LongSupplier remainingNanos) {
            projects.requireViewer(project); schema.requireReady();
            var snapshot = request(project, requestId); sameActor(snapshot);
            var settings = enablements.lockSettings(project); var batch = batch(project, batchId); store.lockBatch(batchId);
            var attempt = store.attempt(project, requestId).orElseThrow(AgentEnablementInvalidStateException::new);
            var binding = store.reservationBinding(project, requestId);
            if (!batchId.equals(attempt.batchId()) || !templateId.equals(attempt.templateId()) || attempt.outcome() != null
                    || !deployment().equals(attempt.deploymentId()) || !batch.costPolicyVersion().equals(attempt.priceVersion())
                    || attempt.reservation() != LiveCostPolicy.reservation(batch.costPolicyVersion()) || settings.revision() != binding.settingsRevision()
                    || !batch.view().enablementId().equals(binding.enablementId())
                    || !attempt.day().equals(Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate())) throw new AgentEnablementInvalidStateException();
            // Read again after lock waits: recovery may already have terminalized the request.
            snapshot = request(project, requestId); sameActor(snapshot); projects.requireViewer(project);
            validateBatch(project, settings, batch); validateRequest(snapshot, batch, templateId, "RUNNING");
            var approval = approval(project, settings.enablementId()); validateApproval(approval, remainingNanos);
            validateLink(snapshot, batch, templateId, settings, approval);
            if (LiveCostPolicy.isContext(batch.costPolicyVersion())) store.requireSerialProgress(batch, requestId);
            if (!binding.credentialVersion().equals(approval.acknowledgement().credentialVersion())) throw new AgentEnablementInvalidStateException();
            return new SendApproval(binding.credentialVersion(), new HashSet<>(approval.acknowledgement().allowedResponseModels()));
    }
    @Override public Outcome settle(UUID project, UUID request, String price, LiveTokenUsage usage) { return finish(project, request, price, usage); }
    @Override public Outcome unknown(UUID project, UUID request) { return finish(project, request, null, null); }
    @Override public void stopBatch(UUID project, UUID batchId, UUID requestId, String code) {
        cleanupWrite(project, "agent.live.batch_stopped", () -> {
            schema.requireReady(); var snapshot = request(project, requestId); sameActor(snapshot);
            enablements.lockSettings(project); var batch = batch(project, batchId); store.lockBatch(batchId);
            var link = links.find(project, requestId).orElseThrow(AgentNotFoundException::new);
            if (!batchId.equals(link.batchId()) || !batch.view().enablementId().equals(link.enablementId())
                    || !batch.costPolicyVersion().equals(snapshot.view().bindings().priceCatalogVersion())) throw new AgentConflictException();
            // A committed claim can fail preflight before it ever reserves a provider attempt.
            if (LiveCostPolicy.isContext(batch.costPolicyVersion())) stop(batch, snapshot, code);
            return null;
        });
    }
    private Outcome finish(UUID project, UUID id, String price, LiveTokenUsage usage) {
        return cleanupWrite(project, "agent.live.finished", () -> {
            schema.requireReady(); var snapshot = request(project, id); sameActor(snapshot);
            enablements.lockSettings(project);
            var initial = store.attempt(project, id).orElseThrow(AgentNotFoundException::new);
            store.lockBatch(initial.batchId()); var attempt = store.attempt(project, id).orElseThrow();
            if (attempt.outcome() != null) return new Outcome(attempt.outcome(), attempt.actual());
            // Closing/revoking, removing membership or crossing midnight cannot erase an incurred charge.
            store.lockBudget("DEPLOYMENT", attempt.deploymentId(), attempt.day()); store.lockBudget("PROJECT", project, attempt.day());
            Long cost = null;
            if (attempt.priceVersion().equals(price)) {
                try { cost = LiveCostPolicy.cost(attempt.priceVersion(), usage); } catch (AgentInvalidException invalid) { /* Untrusted accounting remains unknown. */ }
            }
            store.finish(attempt, cost == null ? null : usage, cost, Instant.now(clock));
            if (LiveCostPolicy.CONTEXT_VERSION.equals(attempt.priceVersion())
                    && (cost == null || usage.inputTokens() > LiveCostPolicy.POSTCALL_INPUT_THRESHOLD)) {
                stop(batch(project, attempt.batchId()), snapshot, cost == null ? "OUTPUT_INVALID" : "INPUT_TOKEN_LIMIT");
            }
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
    private void validateRequest(AgentSnapshot snapshot, LiveAccountingStore.Batch batch, UUID templateId, String state) {
        var expected = template(batch, templateId); var bindings = snapshot.view().bindings();
        String prompt = "PR_SUMMARY".equals(snapshot.view().purpose()) ? SUMMARY_PROMPT : EXPLANATION_PROMPT;
        if (!state.equals(snapshot.view().state()) || !LiveCostPolicy.modelProfile(batch.costPolicyVersion()).equals(bindings.modelProfileVersion())
                || !"deepseek-flash".equals(bindings.modelId()) || !"responses-v1-restricted".equals(bindings.modelProtocolVersion())
                || !batch.costPolicyVersion().equals(bindings.priceCatalogVersion()) || !prompt.equals(bindings.promptVersion())
                || !prompt.equals(snapshot.input().promptVersion())
                || !requestDigest(snapshot.view().projectId(), batch.view().id(), templateId).equals(bindings.inputDigest())
                || !expected.selection().equals(LiveManifest.selection(snapshot))
                || !expected.payloadSha256().equals(LiveManifest.payload(mapper, snapshot))
                || !expected.candidatesSha256().equals(LiveManifest.hash(mapper, snapshot.candidates()))) throw new AgentEnablementInvalidStateException();
    }
    private void validateLink(AgentSnapshot snapshot, LiveAccountingStore.Batch batch, UUID template,
            AgentEnablementStore.Settings settings, AgentEnablementStore.Approval approval) {
        var link = links.find(snapshot.view().projectId(), snapshot.view().id()).orElseThrow(AgentEnablementInvalidStateException::new);
        if (!batch.view().id().equals(link.batchId()) || !template.equals(link.templateId())
                || !approval.id().equals(link.enablementId()) || settings.revision() != link.settingsRevision()
                || !approval.acknowledgement().credentialVersion().equals(link.credentialVersion())) throw new AgentEnablementInvalidStateException();
    }
    private boolean fits(LiveAccountingStore.Usage value, long limit, long reservation) {
        return value.reserved() >= 0 && value.spent() >= 0 && value.reserved() <= limit && value.spent() <= limit - value.reserved()
            && reservation <= limit - value.reserved() - value.spent();
    }
    private void validateBatch(UUID project, AgentEnablementStore.Settings settings, LiveAccountingStore.Batch batch) {
        if (!LiveCostPolicy.supported(batch.costPolicyVersion())
                || !batch.costPolicyVersion().equals(approval(project, batch.view().enablementId()).acknowledgement().priceCatalogVersion())
                || !enabled || !settings.enabled() || !batch.view().enablementId().equals(settings.enablementId())
                || batch.view().revoked() || !Instant.now(clock).isBefore(batch.view().expiresAt())
                || !deployment().equals(batch.deploymentId()) || batch.inventoryProof() == null
                || !inventory.resolve(project, batch.inventoryId(), batch.view().manifestSha256())
                    .filter(batch.inventoryProof()::equals).isPresent()) throw new AgentEnablementInvalidStateException();
    }
    private void validateApproval(AgentEnablementStore.Approval value) {
        validateApproval(value, null);
    }
    private void validateApproval(AgentEnablementStore.Approval value, LongSupplier remainingNanos) {
        var input = value.acknowledgement();
        // Context-policy admission is current-only. Historical records and incurred settlement remain unchanged.
        if (LiveCostPolicy.isContext(input.priceCatalogVersion()) && !PersonalEnablementPolicy.currentMapping(input)) {
            throw new AgentEnablementInvalidStateException();
        }
        var credential = remainingNanos == null ? credentials.current() : credentials.current(remainingNanos);
        Instant now = Instant.now(clock);
        if (!deployment().equals(value.deploymentId()) || value.revokedAt() != null || !now.isBefore(input.expiresAt())
                || !now.isBefore(input.priceCatalogExpiresAt()) || !LiveCostPolicy.supported(input.priceCatalogVersion())
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
    private void stop(LiveAccountingStore.Batch batch, AgentSnapshot snapshot, String code) {
        store.stop(snapshot.view().projectId(), batch.view().id(), snapshot.view().id(), code, Instant.now(clock));
        record(snapshot.view().projectId(), snapshot.view().requesterId(), snapshot.view().traceId(), "agent.live.batch_stopped",
            Map.of("batchId", batch.view().id(), "requestId", snapshot.view().id(), "code", code));
    }
    private void record(UUID project, UUID actor, String trace, String action, Map<String, Object> metadata) {
        audit.record(new AuditEvent(actor, project, action, AuditResult.SUCCESS, trace, metadata));
    }
    private <T> T write(UUID project, String action, Supplier<T> operation) {
        try { return transaction.execute(status -> operation.get()); }
        catch (RuntimeException failure) {
            rejected(project, action, failure, false);
            throw failure;
        }
    }
    private <T> T boundedWrite(UUID project, String action, LongSupplier remainingNanos, Supplier<T> operation) {
        try { return database.transaction(remainingNanos, false, operation); }
        catch (RuntimeException failure) {
            rejected(project, action, failure, true);
            throw failure;
        }
    }
    private <T> T cleanupWrite(UUID project, String action, Supplier<T> operation) {
        try { return database.cleanup(operation); }
        catch (RuntimeException failure) {
            rejected(project, action, failure, true);
            throw failure;
        }
    }
    private void rejected(UUID project, String action, RuntimeException failure, boolean bounded) {
        Supplier<Void> record = () -> {
            audit.record(new AuditEvent(actors.currentActor().id(), project,
                "agent.live.write_rejected", failure instanceof AgentQuotaExceededException || failure instanceof AgentConflictException ? AuditResult.CONFLICT
                    : failure instanceof io.github.aiarchguard.platform.project.ProjectPermissionDeniedException
                        || failure instanceof AgentEnablementInvalidStateException ? AuditResult.DENIED : AuditResult.FAILURE,
                traces.currentTraceId(), Map.of("operation", action, "code", failure instanceof AgentQuotaExceededException ? "QUOTA_EXHAUSTED" : "DENIED_OR_UNAVAILABLE")));
            return null;
        };
        try {
            if (bounded) database.cleanup(record);
            else transaction.executeWithoutResult(status -> record.get());
        } catch (RuntimeException unavailableAudit) { throw new AgentUnavailableException(); }
    }
}
