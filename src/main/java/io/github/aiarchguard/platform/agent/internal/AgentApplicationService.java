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
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String SUMMARY_PROMPT_VERSION = "pr-summary-0.1.0";
    private static final String SCHEMA_VERSION = "0.1.0";
    private static final String MODEL_PROFILE = "disabled-or-test-fake-0.1.0";
    private static final String MODEL_PROTOCOL = "responses-v1-restricted";
    private static final String MODEL_ID = "deterministic-fake-or-disabled";
    private static final String PRICE_CATALOG = "synthetic-0.1.0";
    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final ScanJobOperations jobs;
    private final ReportSubmissionOperations reports;
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
            ReportSubmissionOperations reports,
            FindingOperations findings, DocumentOperations documents, AgentStore store,
            AgentSchemaAvailability schema, AgentModelPort model,
            AgentWorker worker, @Qualifier("agentTaskExecutor") Executor executor, AuditRecorder audit,
            TraceIdProvider traceIds, ObjectMapper mapper, Clock clock,
            @Value("${archguard.agent.enabled:false}") boolean enabled) {
        this.projects = projects; this.actors = actors; this.jobs = jobs; this.reports = reports;
        this.findings = findings;
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
        boolean summary = "PR_SUMMARY".equals(request.purpose());
        UUID requester = actors.currentActor().id();
        ScanJobView scan = jobs.get(projectId, request.scanJobId());
        if (scan.status() != ScanJobStatus.SUCCEEDED || !request.reportSha256().equals(scan.reportSha256())) {
            throw new AgentNotFoundException();
        }
        if (summary && !reports.matchesCompletedPrRevision(projectId, scan.id(),
                scan.reportSha256(), request.prHeadRevisionId())) throw new AgentNotFoundException();
        List<UUID> selectedIds = request.findingIds().stream().sorted().toList();
        List<FindingView> selectedFindings = selectedIds.stream()
            .map(findingId -> findings.get(projectId, scan.id(), findingId)).toList();
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
        List<AgentModelPort.FindingInput> findingInput = new ArrayList<>();
        for (FindingView finding : selectedFindings) {
            List<AgentModelPort.EvidenceInput> evidenceInput = new ArrayList<>();
            for (UUID evidenceId : finding.evidenceIds().stream().limit(20).toList()) {
                EvidenceView evidence = findings.evidence(projectId, scan.id(), evidenceId);
                String citation = alias();
                candidates.add(new AgentSnapshot.Candidate(citation, "SCANNER_EVIDENCE", projectId,
                    scan.id(), scan.reportSha256(), evidence.id(), null, null, null, null, evidence.kind()));
                evidenceInput.add(new AgentModelPort.EvidenceInput(citation, evidence.kind(),
                    evidence.kind(), evidence.location() == null ? null : evidence.location().startLine(),
                    evidence.location() == null ? null : evidence.location().endLine()));
            }
            findingInput.add(new AgentModelPort.FindingInput(alias(), finding.ruleId(),
                finding.ruleVersion(), finding.severity(), finding.message(), finding.subjectId(),
                List.copyOf(evidenceInput)));
        }
        List<AgentModelPort.DocumentInput> documentInput = new ArrayList<>();
        if (!versions.isEmpty()) {
            Set<String> seenFragments = new LinkedHashSet<>();
            for (FindingView finding : selectedFindings) {
                String query = finding.ruleId().length() >= 2 ? finding.ruleId().substring(0,
                    Math.min(120, finding.ruleId().length())) : finding.message().substring(0,
                    Math.min(120, finding.message().length()));
                for (DocumentFragmentView fragment : documents.search(projectId,
                        versions.stream().map(DocumentVersionView::id).toList(), query)) {
                    String identity = fragment.documentVersionId() + ":" + fragment.fragmentIndex();
                    if (!seenFragments.add(identity)) continue;
                    String citation = alias();
                    candidates.add(new AgentSnapshot.Candidate(citation, "PROJECT_DOCUMENT", projectId, null,
                        null, null, fragment.documentVersionId(), fragment.contentSha256(),
                        fragment.fragmentIndex(), fragment.fragmentSha256(),
                        "Document fragment " + fragment.fragmentIndex()));
                    documentInput.add(new AgentModelPort.DocumentInput(citation, fragment.contentSha256(),
                        fragment.content()));
                    if (documentInput.size() == 10) break;
                }
                if (documentInput.size() == 10) break;
            }
        }
        AgentModelPort.FindingInput first = findingInput.getFirst();
        String promptVersion = summary ? SUMMARY_PROMPT_VERSION : PROMPT_VERSION;
        AgentModelPort.ModelInput input = summary
            ? new AgentModelPort.ModelInput("PR_SUMMARY", SCHEMA_VERSION, promptVersion,
                null, null, null, null, null, null, List.of(), List.copyOf(documentInput),
                List.copyOf(findingInput))
            : new AgentModelPort.ModelInput("FINDING_EXPLANATION", SCHEMA_VERSION, promptVersion,
                first.findingRef(), first.ruleId(), first.ruleVersion(), first.severity(),
                first.message(), first.subject(), first.evidence(), List.copyOf(documentInput), null);
        int conservativeTokens = json(input).getBytes(StandardCharsets.UTF_8).length;
        String failureCode = !enabled ? "MODEL_DISABLED" : !model.syntheticOnly() || !model.available() ? "MODEL_UNAVAILABLE"
            : conservativeTokens > 8000 ? "QUOTA_EXHAUSTED" : null;
        AgentRequestView.AgentFailure failure = failureCode == null ? null
            : new AgentRequestView.AgentFailure(failureCode, message(failureCode));
        AgentRequestView.VersionBindings bindings = new AgentRequestView.VersionBindings(scan.id(),
            scan.reportSha256(), request.prHeadRevisionId(), selectedIds, documentBindings, promptVersion,
            MODEL_PROFILE, MODEL_PROTOCOL, MODEL_ID, SCHEMA_VERSION, PRICE_CATALOG, digest);
        AgentRequestView view = new AgentRequestView(id, projectId, requester, trace,
            request.purpose(), failure == null ? "QUEUED" : "FAILED", bindings, null, failure,
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
                "scanJobId", scan.id(), "reportSha256", scan.reportSha256(), "findingIds", selectedIds,
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
        boolean explanation = request != null && "FINDING_EXPLANATION".equals(request.purpose());
        boolean summary = request != null && "PR_SUMMARY".equals(request.purpose());
        if (key == null || key.isBlank() || key.length() > 128
                || key.chars().anyMatch(c -> c < 33 || c > 126)
                || (!explanation && !summary)
                || request.scanJobId() == null || request.reportSha256() == null
                || !request.reportSha256().matches("[a-f0-9]{64}")
                || (explanation && request.prHeadRevisionId() != null)
                || (summary && request.prHeadRevisionId() == null)
                || request.findingIds() == null || request.findingIds().isEmpty()
                || request.findingIds().size() > (summary ? 20 : 1)
                || request.findingIds().contains(null)
                || new HashSet<>(request.findingIds()).size() != request.findingIds().size()
                || request.documentVersionIds() == null
                || request.documentVersionIds().size() > 10 || request.documentVersionIds().contains(null)
                || new HashSet<>(request.documentVersionIds()).size() != request.documentVersionIds().size()) {
            throw new AgentInvalidException();
        }
    }

    private static String canonicalDigestInput(CreateAgentRequest request,
            List<AgentRequestView.DocumentVersionBinding> versions) {
        if ("PR_SUMMARY".equals(request.purpose())) {
            StringBuilder value = new StringBuilder("PR_SUMMARY|").append(request.scanJobId())
                .append('|').append(request.reportSha256()).append('|').append(request.prHeadRevisionId());
            request.findingIds().stream().sorted().forEach(id -> value.append('|').append(id));
            value.append('|').append(SUMMARY_PROMPT_VERSION).append('|').append(SCHEMA_VERSION)
                .append('|').append(MODEL_PROFILE);
            versions.forEach(v -> value.append('|').append(v.documentVersionId())
                .append(':').append(v.contentSha256()));
            return value.toString();
        }
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
