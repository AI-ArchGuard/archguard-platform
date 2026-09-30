package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentConflictException;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.AgentOperations;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.CreateAgentRequest;
import io.github.aiarchguard.platform.agentdocument.DocumentFragmentView;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.finding.EvidenceView;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.finding.FindingView;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import io.github.aiarchguard.platform.scanjob.ScanJobView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class AgentApplicationService implements AgentOperations {
    private static final String PROMPT_VERSION = "finding-explanation-0.1.0";
    private static final String SCHEMA_VERSION = "0.1.0";
    private static final String MODEL_PROFILE = "disabled-or-test-fake-0.1.0";
    private static final String MODEL_PROTOCOL = "responses-v1-restricted";
    private static final String MODEL_ID = "deterministic-fake-or-disabled";
    private static final String PRICE_CATALOG = "synthetic-0.1.0";
    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final ScanJobOperations jobs;
    private final FindingOperations findings;
    private final DocumentOperations documents;
    private final AgentStore store;
    private final AgentSchemaAvailability schema;
    private final AgentModelPort model;
    private final AgentWorker worker;
    private final Executor executor;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;

    AgentApplicationService(ProjectAuthorization projects, CurrentActorProvider actors, ScanJobOperations jobs,
            FindingOperations findings, DocumentOperations documents, AgentStore store,
            AgentSchemaAvailability schema, AgentModelPort model,
            AgentWorker worker, @Qualifier("agentTaskExecutor") Executor executor, AuditRecorder audit,
            TraceIdProvider traceIds, ObjectMapper mapper, Clock clock,
            @Value("${archguard.agent.enabled:false}") boolean enabled) {
        this.projects = projects; this.actors = actors; this.jobs = jobs; this.findings = findings;
        this.documents = documents; this.store = store; this.schema = schema;
        this.model = model; this.worker = worker;
        this.executor = executor; this.audit = audit; this.traceIds = traceIds; this.mapper = mapper;
        this.clock = clock; this.enabled = enabled;
    }

    @Override
    @Transactional
    public AgentRequestView create(UUID projectId, String key, CreateAgentRequest request) {
        projects.requireViewer(projectId);
        schema.requireReady();
        validate(key, request);
        UUID requester = actors.currentActor().id();
        ScanJobView scan = jobs.get(projectId, request.scanJobId());
        if (scan.status() != ScanJobStatus.SUCCEEDED || !request.reportSha256().equals(scan.reportSha256())) {
            throw new AgentNotFoundException();
        }
        UUID findingId = request.findingIds().getFirst();
        FindingView finding = findings.get(projectId, scan.id(), findingId);
        List<DocumentVersionView> versions = new ArrayList<>();
        for (UUID versionId : request.documentVersionIds().stream().sorted().toList()) {
            versions.add(documents.getVersionById(projectId, versionId));
        }
        List<AgentRequestView.DocumentVersionBinding> documentBindings = versions.stream()
            .map(v -> new AgentRequestView.DocumentVersionBinding(v.id(), v.contentSha256())).toList();
        String digest = sha256(canonicalDigestInput(request, documentBindings));
        var existing = store.findByKey(projectId, requester, key);
        if (existing.isPresent()) {
            if (!existing.get().view().bindings().inputDigest().equals(digest)) throw new AgentConflictException();
            return existing.get().view();
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String trace = traceIds.currentTraceId();
        List<AgentSnapshot.Candidate> candidates = new ArrayList<>();
        List<AgentModelPort.EvidenceInput> evidenceInput = new ArrayList<>();
        for (UUID evidenceId : finding.evidenceIds().stream().limit(20).toList()) {
            EvidenceView evidence = findings.evidence(projectId, scan.id(), evidenceId);
            String alias = alias();
            candidates.add(new AgentSnapshot.Candidate(alias, "SCANNER_EVIDENCE", projectId, scan.id(),
                scan.reportSha256(), evidence.id(), null, null, null, null, evidence.kind()));
            evidenceInput.add(new AgentModelPort.EvidenceInput(alias, evidence.kind(),
                evidence.kind(), evidence.location() == null ? null : evidence.location().startLine(),
                evidence.location() == null ? null : evidence.location().endLine()));
        }
        List<AgentModelPort.DocumentInput> documentInput = new ArrayList<>();
        if (!versions.isEmpty()) {
            String query = finding.ruleId().length() >= 2 ? finding.ruleId().substring(0,
                Math.min(120, finding.ruleId().length())) : finding.message().substring(0,
                Math.min(120, finding.message().length()));
            for (DocumentFragmentView fragment : documents.search(projectId,
                    versions.stream().map(DocumentVersionView::id).toList(), query)) {
                String alias = alias();
                candidates.add(new AgentSnapshot.Candidate(alias, "PROJECT_DOCUMENT", projectId, null,
                    null, null, fragment.documentVersionId(), fragment.contentSha256(),
                    fragment.fragmentIndex(), fragment.fragmentSha256(), "Document fragment " + fragment.fragmentIndex()));
                documentInput.add(new AgentModelPort.DocumentInput(alias, fragment.contentSha256(),
                    fragment.content()));
            }
        }
        AgentModelPort.ModelInput input = new AgentModelPort.ModelInput("FINDING_EXPLANATION",
            SCHEMA_VERSION, PROMPT_VERSION, alias(), finding.ruleId(), finding.ruleVersion(),
            finding.severity(), finding.message(), finding.subjectId(), List.copyOf(evidenceInput),
            List.copyOf(documentInput));
        int conservativeTokens = json(input).getBytes(StandardCharsets.UTF_8).length;
        String failureCode = !enabled ? "MODEL_DISABLED" : !model.syntheticOnly() || !model.available() ? "MODEL_UNAVAILABLE"
            : conservativeTokens > 8000 ? "QUOTA_EXHAUSTED" : null;
        AgentRequestView.AgentFailure failure = failureCode == null ? null
            : new AgentRequestView.AgentFailure(failureCode, message(failureCode));
        AgentRequestView.VersionBindings bindings = new AgentRequestView.VersionBindings(scan.id(),
            scan.reportSha256(), null, List.of(findingId), documentBindings, PROMPT_VERSION,
            MODEL_PROFILE, MODEL_PROTOCOL, MODEL_ID, SCHEMA_VERSION, PRICE_CATALOG, digest);
        AgentRequestView view = new AgentRequestView(id, projectId, requester, trace,
            "FINDING_EXPLANATION", failure == null ? "QUEUED" : "FAILED", bindings, null, failure,
            null, now, now);
        AgentSnapshot snapshot = new AgentSnapshot(view, input, List.copyOf(candidates));
        if (!store.insert(snapshot, key)) {
            AgentRequestView replay = store.findByKey(projectId, requester, key).orElseThrow().view();
            if (!replay.bindings().inputDigest().equals(digest)) throw new AgentConflictException();
            return replay;
        }
        audit.record(new AuditEvent(requester, projectId, "agent.request.created",
            failure == null ? AuditResult.SUCCESS : AuditResult.DENIED, trace,
            Map.of("requestId", id, "state", view.state(), "inputDigest", digest,
                "failureCode", failureCode == null ? "NONE" : failureCode,
                "scanJobId", scan.id(), "reportSha256", scan.reportSha256(), "findingId", findingId,
                "documentVersionIds", versions.stream().map(DocumentVersionView::id).toList())));
        if (failure == null) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { executor.execute(() -> worker.process(projectId, id)); }
                catch (RuntimeException rejected) { worker.failQueued(projectId, id, "MODEL_UNAVAILABLE"); }
            }
        });
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public AgentRequestView get(UUID projectId, UUID requestId) {
        projects.requireViewer(projectId);
        schema.requireReady();
        return store.find(projectId, requestId).map(AgentSnapshot::view).orElseThrow(AgentNotFoundException::new);
    }

    private static void validate(String key, CreateAgentRequest request) {
        if (key == null || key.isBlank() || key.length() > 128
                || key.chars().anyMatch(c -> c < 33 || c > 126)
                || request == null || !"FINDING_EXPLANATION".equals(request.purpose())
                || request.scanJobId() == null || request.reportSha256() == null
                || !request.reportSha256().matches("[a-f0-9]{64}") || request.prHeadRevisionId() != null
                || request.findingIds() == null || request.findingIds().size() != 1
                || request.findingIds().getFirst() == null || request.documentVersionIds() == null
                || request.documentVersionIds().size() > 10 || request.documentVersionIds().contains(null)
                || new HashSet<>(request.documentVersionIds()).size() != request.documentVersionIds().size()) {
            throw new AgentInvalidException();
        }
    }

    private static String canonicalDigestInput(CreateAgentRequest request,
            List<AgentRequestView.DocumentVersionBinding> versions) {
        StringBuilder value = new StringBuilder("FINDING_EXPLANATION|").append(request.scanJobId())
            .append('|').append(request.reportSha256()).append('|').append(request.findingIds().getFirst())
            .append('|').append(PROMPT_VERSION).append('|').append(SCHEMA_VERSION).append('|').append(MODEL_PROFILE);
        versions.forEach(v -> value.append('|').append(v.documentVersionId()).append(':').append(v.contentSha256()));
        return value.toString();
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent input encoding failed", exception); }
    }

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    static String alias() { return "c-" + UUID.randomUUID().toString(); }

    static String message(String code) { return switch (code) {
        case "MODEL_DISABLED" -> "Agent model calls are disabled.";
        case "MODEL_UNAVAILABLE" -> "Agent model is unavailable.";
        case "QUOTA_EXHAUSTED" -> "Agent request exceeds its configured limit.";
        case "MODEL_TIMEOUT" -> "Agent model timed out.";
        case "OUTPUT_INVALID" -> "Agent output did not pass validation.";
        case "CITATION_INVALID" -> "Agent citation did not pass validation.";
        case "AUTHORIZATION_REVOKED" -> "Project authorization was revoked.";
        default -> "Agent request failed.";
    }; }
}
