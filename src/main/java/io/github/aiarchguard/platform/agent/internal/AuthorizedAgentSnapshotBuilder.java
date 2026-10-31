package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.CreateAgentRequest;
import io.github.aiarchguard.platform.agentdocument.DocumentFragmentView;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.finding.EvidenceView;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.finding.FindingView;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import io.github.aiarchguard.platform.scanjob.ScanJobView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 授权资源元数据与最小投影；不负责启用、幂等存储、失败选择或调度。 */
final class AuthorizedAgentSnapshotBuilder {
    private static final String PROMPT_VERSION = "finding-explanation-0.1.0";
    private static final String SUMMARY_PROMPT_VERSION = "pr-summary-0.1.0";
    private static final String SCHEMA_VERSION = "0.1.0";
    private static final String MODEL_PROFILE = "disabled-or-test-fake-0.1.0";
    private static final String MODEL_PROTOCOL = "responses-v1-restricted";
    private static final String MODEL_ID = "deterministic-fake-or-disabled";
    private static final String PRICE_CATALOG = "synthetic-0.1.0";
    static final SnapshotProfile LEGACY = new SnapshotProfile(PROMPT_VERSION, SUMMARY_PROMPT_VERSION,
        MODEL_PROFILE, MODEL_PROTOCOL, MODEL_ID, PRICE_CATALOG, false);
    static final SnapshotProfile DAILY = new SnapshotProfile(LiveAccountingOperations.EXPLANATION_PROMPT,
        LiveAccountingOperations.SUMMARY_PROMPT, io.github.aiarchguard.platform.agent.LiveCostPolicy.CONTEXT_MODEL_PROFILE,
        MODEL_PROTOCOL, "deepseek-flash", io.github.aiarchguard.platform.agent.LiveCostPolicy.CONTEXT_VERSION, true);
    record SnapshotIdentity(UUID requestId, UUID requesterId, String traceId, Instant createdAt) {}
    record SnapshotProfile(String explanationPrompt, String summaryPrompt, String modelProfile, String protocol,
        String modelId, String priceVersion, boolean rejectWholeDocumentProjection) {}
    record AuthorizedSelection(UUID projectId, CreateAgentRequest request, ScanJobView scan,
            List<FindingView> findings, List<DocumentVersionView> versions) {
        AuthorizedSelection {
            request = new CreateAgentRequest(request.purpose(), request.scanJobId(), request.reportSha256(),
                request.prHeadRevisionId(), List.copyOf(request.findingIds()), List.copyOf(request.documentVersionIds()));
            findings = findings.stream().map(f -> new FindingView(f.id(), f.projectId(), f.jobId(),
                f.scannerFindingId(), f.fingerprint(), f.ruleId(), f.ruleVersion(), f.severity(), f.subjectId(),
                f.message(), f.location(), f.disposition(), f.version(), List.copyOf(f.evidenceIds()))).toList();
            versions = List.copyOf(versions);
        }
        List<AgentRequestView.DocumentVersionBinding> documentBindings() {
            return versions.stream().map(v -> new AgentRequestView.DocumentVersionBinding(v.id(), v.contentSha256())).toList();
        }
    }
    private final ScanJobOperations jobs;
    private final ReportSubmissionOperations reports;
    private final FindingOperations findings;
    private final DocumentOperations documents;
    private final ObjectMapper mapper;
    AuthorizedAgentSnapshotBuilder(ScanJobOperations jobs, ReportSubmissionOperations reports,
            FindingOperations findings, DocumentOperations documents, ObjectMapper mapper) {
        this.jobs=jobs; this.reports=reports; this.findings=findings; this.documents=documents; this.mapper=mapper;
    }
    AuthorizedSelection authorize(UUID projectId, CreateAgentRequest request) {
        boolean summary = "PR_SUMMARY".equals(request.purpose());
        ScanJobView scan = jobs.get(projectId, request.scanJobId());
        if (scan == null || !projectId.equals(scan.projectId()) || !request.scanJobId().equals(scan.id())
                || scan.status() != ScanJobStatus.SUCCEEDED || !request.reportSha256().equals(scan.reportSha256())) {
            throw new AgentNotFoundException();
        }
        if (summary && !reports.matchesCompletedPrRevision(projectId, scan.id(),
                scan.reportSha256(), request.prHeadRevisionId())) throw new AgentNotFoundException();
        List<UUID> selectedIds = request.findingIds().stream().sorted().toList();
        List<FindingView> selectedFindings = selectedIds.stream()
            .map(findingId -> findings.get(projectId, scan.id(), findingId)).toList();
        for (int i = 0; i < selectedFindings.size(); i++) {
            FindingView finding = selectedFindings.get(i);
            if (finding == null || !selectedIds.get(i).equals(finding.id()) || !projectId.equals(finding.projectId())
                    || !scan.id().equals(finding.jobId())) throw new AgentNotFoundException();
        }
        List<DocumentVersionView> versions = new ArrayList<>();
        for (UUID versionId : request.documentVersionIds().stream().sorted().toList()) {
            DocumentVersionView version = documents.getVersionById(projectId, versionId);
            if (version == null || !versionId.equals(version.id()) || !projectId.equals(version.projectId())) throw new AgentNotFoundException();
            versions.add(version);
        }
        return new AuthorizedSelection(projectId, request, scan, selectedFindings, versions);
    }
    String legacyDigest(AuthorizedSelection selection) {
        return sha256(canonicalDigestInput(selection.request(), selection.documentBindings()));
    }
    String dailyDigest(UUID projectId, UUID ownerId, UUID grantId, String runtimeBindingDigest,
            AuthorizedSelection selection, SnapshotProfile profile) {
        java.util.LinkedHashMap<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("domain", "personal-project-agent-request-0.1.0");
        value.put("projectId", projectId); value.put("ownerId", ownerId); value.put("grantId", grantId);
        value.put("runtimeBindingDigest", runtimeBindingDigest);
        value.put("purpose", selection.request().purpose()); value.put("scanJobId", selection.scan().id());
        value.put("reportSha256", selection.scan().reportSha256());
        value.put("prHeadRevisionId", selection.request().prHeadRevisionId());
        value.put("findingIds", selection.findings().stream().map(FindingView::id).toList());
        value.put("documentVersions", selection.documentBindings().stream().map(binding -> {
            java.util.LinkedHashMap<String, Object> document = new java.util.LinkedHashMap<>();
            document.put("documentVersionId", binding.documentVersionId());
            document.put("contentSha256", binding.contentSha256());
            return document;
        }).toList());
        value.put("promptVersion", "PR_SUMMARY".equals(selection.request().purpose()) ? profile.summaryPrompt() : profile.explanationPrompt());
        value.put("outputSchemaVersion", SCHEMA_VERSION); value.put("modelProfileVersion", profile.modelProfile());
        value.put("modelProtocolVersion", profile.protocol()); value.put("modelId", profile.modelId());
        value.put("priceCatalogVersion", profile.priceVersion());
        try { return sha256(mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value)); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent digest encoding failed", exception); }
    }
    /** RED 接缝：历史重放摘要只能读取已保存版本；实现留待完整 RED 通过后。 */
    String dailyReplayDigest(UUID projectId, UUID ownerId, UUID grantId, String runtimeBindingDigest,
            CreateAgentRequest incoming, AgentRequestView.VersionBindings stored) {
        throw new io.github.aiarchguard.platform.agent.AgentUnavailableException();
    }
    AgentSnapshot build(AuthorizedSelection selection, SnapshotIdentity identity, SnapshotProfile profile, String inputDigest) {
        UUID projectId = selection.projectId();
        CreateAgentRequest request = selection.request();
        ScanJobView scan = selection.scan();
        List<FindingView> selectedFindings = selection.findings();
        List<DocumentVersionView> versions = selection.versions();
        boolean summary = "PR_SUMMARY".equals(request.purpose());
        List<AgentSnapshot.Candidate> candidates = new ArrayList<>();
        List<AgentModelPort.FindingInput> findingInput = new ArrayList<>();
        for (FindingView finding : selectedFindings) {
            List<AgentModelPort.EvidenceInput> evidenceInput = new ArrayList<>();
            for (UUID evidenceId : finding.evidenceIds().stream().limit(20).toList()) {
                EvidenceView evidence = findings.evidence(projectId, scan.id(), evidenceId);
                if (profile.rejectWholeDocumentProjection() && (evidence == null || !evidenceId.equals(evidence.id())
                        || !projectId.equals(evidence.projectId()) || !scan.id().equals(evidence.jobId()))) throw new AgentNotFoundException();
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
        if (profile.rejectWholeDocumentProjection()) {
            if (scan.completedAt() == null) throw new AgentNotFoundException();
            java.util.Map<UUID, DocumentVersionView> verified = new java.util.LinkedHashMap<>();
            try {
                for (DocumentVersionView version : versions) {
                    LiveResourceAuthorizer.validateVersion(projectId,
                        new AgentRequestView.DocumentVersionBinding(version.id(), version.contentSha256()), version);
                    verified.put(version.id(), version);
                }
                var fragments = LiveResourceAuthorizer.replayFragments(documents, projectId, selectedFindings, verified);
                LiveResourceAuthorizer.rejectWholeDocuments(verified, fragments);
                for (DocumentFragmentView fragment : fragments.values()) {
                    String citation = alias();
                    candidates.add(new AgentSnapshot.Candidate(citation, "PROJECT_DOCUMENT", projectId, null,
                        null, null, fragment.documentVersionId(), fragment.contentSha256(), fragment.fragmentIndex(),
                        fragment.fragmentSha256(), "Document fragment " + fragment.fragmentIndex()));
                    documentInput.add(new AgentModelPort.DocumentInput(citation, fragment.contentSha256(), fragment.content()));
                }
            } catch (io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException invalid) {
                throw new AgentInvalidException();
            }
        } else if (!versions.isEmpty()) {
            Set<String> seenFragments = new LinkedHashSet<>();
            for (FindingView finding : selectedFindings) {
                String query = finding.ruleId().length() >= 2 ? finding.ruleId().substring(0,
                    Math.min(120, finding.ruleId().length())) : finding.message().substring(0,
                    Math.min(120, finding.message().length()));
                for (DocumentFragmentView fragment : documents.search(projectId,
                        versions.stream().map(DocumentVersionView::id).toList(), query)) {
                    String fragmentIdentity = fragment.documentVersionId() + ":" + fragment.fragmentIndex();
                    if (!seenFragments.add(fragmentIdentity)) continue;
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
        String promptVersion = summary ? profile.summaryPrompt() : profile.explanationPrompt();
        AgentModelPort.ModelInput input = summary
            ? new AgentModelPort.ModelInput("PR_SUMMARY", SCHEMA_VERSION, promptVersion,
                null, null, null, null, null, null, List.of(), List.copyOf(documentInput),
                List.copyOf(findingInput))
            : new AgentModelPort.ModelInput("FINDING_EXPLANATION", SCHEMA_VERSION, promptVersion,
                first.findingRef(), first.ruleId(), first.ruleVersion(), first.severity(),
                first.message(), first.subject(), first.evidence(), List.copyOf(documentInput), null);
        var bindings = new AgentRequestView.VersionBindings(scan.id(), scan.reportSha256(), request.prHeadRevisionId(),
            selectedFindings.stream().map(FindingView::id).toList(), selection.documentBindings(), promptVersion,
            profile.modelProfile(), profile.protocol(), profile.modelId(), SCHEMA_VERSION, profile.priceVersion(), inputDigest);
        var view = new AgentRequestView(identity.requestId(), projectId, identity.requesterId(), identity.traceId(),
            request.purpose(), "QUEUED", bindings, null, null, null, identity.createdAt(), identity.createdAt());
        return new AgentSnapshot(view, input, List.copyOf(candidates));
    }
    /** 排序资源选择并绑定内容版本，避免同一集合的顺序差异制造幂等冲突；请求随机引用别名不参与该摘要。 */
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

    private static String sha256(String value) { return AgentApplicationService.sha256(value); }
    private static String alias() { return AgentApplicationService.alias(); }
}
