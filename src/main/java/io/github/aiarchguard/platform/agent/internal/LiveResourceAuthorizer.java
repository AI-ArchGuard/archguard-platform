package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agentdocument.DocumentFragmentView;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.finding.EvidenceView;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.finding.FindingView;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 通过其他模块公开 API 重新核验当前授权和持久化候选；
 * 逐项比对扫描、Finding 所属 Evidence、文档摘要与有界片段，避免把保存的候选直接当事实。
 */
public final class LiveResourceAuthorizer {
    private final ProjectAuthorization projects;
    private final ScanJobOperations jobs;
    private final FindingOperations findings;
    private final DocumentOperations documents;
    private final ReportSubmissionOperations reports;

    public LiveResourceAuthorizer(ProjectAuthorization projects, ScanJobOperations jobs,
            FindingOperations findings, DocumentOperations documents, ReportSubmissionOperations reports) {
        this.projects = Objects.requireNonNull(projects);
        this.jobs = Objects.requireNonNull(jobs);
        this.findings = Objects.requireNonNull(findings);
        this.documents = Objects.requireNonNull(documents);
        this.reports = Objects.requireNonNull(reports);
    }

    /** 创建、准入/发送与保存结果前重复调用；逐项复核当前资源且不缓存授权，已持久化候选也必须重新证明。 */
    public void validate(AgentSnapshot snapshot) {
        require(snapshot != null && snapshot.view() != null && snapshot.input() != null
            && snapshot.candidates() != null && snapshot.candidates().size() <= 410);
        AgentRequestView view = snapshot.view();
        AgentRequestView.VersionBindings bindings = view.bindings();
        AgentModelPort.ModelInput input = snapshot.input();
        boolean summary = "PR_SUMMARY".equals(view.purpose());
        require(summary || "FINDING_EXPLANATION".equals(view.purpose()));
        require(view.projectId() != null && view.requesterId() != null && bindings != null
            && bindings.scanJobId() != null && hash(bindings.reportSha256())
            && Objects.equals(view.purpose(), input.purpose())
            && "0.1.0".equals(input.schemaVersion())
            && Objects.equals(bindings.outputSchemaVersion(), input.schemaVersion())
            && bindings.promptVersion() != null
            && Objects.equals(bindings.promptVersion(), input.promptVersion())
            && input.documents() != null && input.documents().size() <= 10);
        List<UUID> findingIds = bindings.findingIds();
        require(findingIds != null && !findingIds.isEmpty() && findingIds.size() <= (summary ? 20 : 1)
            && findingIds.stream().noneMatch(Objects::isNull) && new HashSet<>(findingIds).size() == findingIds.size()
            && findingIds.equals(findingIds.stream().sorted().toList())
            && (summary ? bindings.prHeadRevisionId() != null : bindings.prHeadRevisionId() == null));
        var access = projects.requireViewerForActor(view.projectId(), view.requesterId());
        require(access != null && view.projectId().equals(access.projectId()));
        var scan = jobs.get(view.projectId(), bindings.scanJobId());
        require(scan != null && view.projectId().equals(scan.projectId())
            && bindings.scanJobId().equals(scan.id()) && scan.status() == ScanJobStatus.SUCCEEDED
            && scan.completedAt() != null && bindings.reportSha256().equals(scan.reportSha256()));
        if (summary) require(reports.matchesCompletedPrRevisionForActor(view.projectId(), view.requesterId(),
            bindings.scanJobId(), bindings.reportSha256(), bindings.prHeadRevisionId()));

        Map<String, AgentSnapshot.Candidate> candidates = candidateIndex(snapshot.candidates(), view.projectId());
        Set<String> used = new HashSet<>();
        Set<String> findingRefs = new HashSet<>();
        List<FindingView> actualFindings = new ArrayList<>();
        if (summary) {
            require(input.findingRef() == null && input.ruleId() == null && input.ruleVersion() == null
                && input.severity() == null && input.message() == null && input.subject() == null
                && input.evidence() != null && input.evidence().isEmpty()
                && input.findings() != null && input.findings().size() == findingIds.size());
        } else require(input.findings() == null);
        for (int index = 0; index < findingIds.size(); index++) {
            FindingView actual = findings.get(view.projectId(), bindings.scanJobId(), findingIds.get(index));
            require(actual != null && findingIds.get(index).equals(actual.id())
                && view.projectId().equals(actual.projectId()) && bindings.scanJobId().equals(actual.jobId()));
            actualFindings.add(actual);
            AgentModelPort.FindingInput selected = summary ? input.findings().get(index)
                : new AgentModelPort.FindingInput(input.findingRef(), input.ruleId(), input.ruleVersion(),
                    input.severity(), input.message(), input.subject(), input.evidence());
            require(selected != null && alias(selected.findingRef()) && findingRefs.add(selected.findingRef())
                && !candidates.containsKey(selected.findingRef())
                && Objects.equals(actual.ruleId(), selected.ruleId())
                && Objects.equals(actual.ruleVersion(), selected.ruleVersion())
                && Objects.equals(actual.severity(), selected.severity())
                && Objects.equals(actual.message(), selected.message())
                && Objects.equals(actual.subjectId(), selected.subject()));
            validateEvidence(view, actual, selected.evidence(), candidates, used);
        }

        Map<UUID, DocumentVersionView> versions = versions(view);
        // No public exact-fragment reader exists. Replay the original bounded public searches and
        // reject a snapshot that no longer reproduces its exact immutable fragment selection.
        Map<String, DocumentFragmentView> fragments = replayFragments(view.projectId(), actualFindings, versions);
        require(input.documents().size() == fragments.size());
        Set<String> usedFragments = new HashSet<>();
        for (AgentModelPort.DocumentInput document : input.documents()) {
            require(document != null && document.excerpt() != null && document.excerpt().length() <= 1024);
            AgentSnapshot.Candidate candidate = candidate(candidates, used, document.citationId());
            require("PROJECT_DOCUMENT".equals(candidate.source()) && candidate.scanJobId() == null
                && candidate.reportSha256() == null && candidate.evidenceId() == null
                && candidate.documentVersionId() != null && candidate.fragmentIndex() != null);
            String identity = fragmentIdentity(candidate.documentVersionId(), candidate.fragmentIndex());
            DocumentFragmentView actual = fragments.get(identity);
            require(actual != null && usedFragments.add(identity)
                && Objects.equals(actual.contentSha256(), candidate.contentSha256())
                && Objects.equals(actual.contentSha256(), document.contentSha256())
                && Objects.equals(actual.fragmentSha256(), candidate.fragmentSha256())
                && Objects.equals(actual.content(), document.excerpt())
                && Objects.equals("Document fragment " + actual.fragmentIndex(), candidate.label()));
        }
        // 每个候选必须恰好被获授权输入消费，拒绝夹带未使用的额外引用。
        require(used.size() == candidates.size());
    }

    /**
     * 按原 Finding 的 Evidence 顺序核对精确归属及最小投影；属于同一扫描或另一个已选 Finding 仍不足以放行。
     */
    private void validateEvidence(AgentRequestView view, FindingView finding,
            List<AgentModelPort.EvidenceInput> selected, Map<String, AgentSnapshot.Candidate> candidates,
            Set<String> used) {
        require(finding.evidenceIds() != null && finding.evidenceIds().stream().noneMatch(Objects::isNull)
            && selected != null && selected.size() <= 20
            && selected.size() == Math.min(20, finding.evidenceIds().size()));
        for (int index = 0; index < selected.size(); index++) {
            AgentModelPort.EvidenceInput input = selected.get(index);
            require(input != null);
            AgentSnapshot.Candidate candidate = candidate(candidates, used, input.citationId());
            // This exact Finding owns the evidence; belonging to another selected Finding is insufficient.
            UUID evidenceId = finding.evidenceIds().get(index);
            require("SCANNER_EVIDENCE".equals(candidate.source()) && evidenceId.equals(candidate.evidenceId())
                && view.bindings().scanJobId().equals(candidate.scanJobId())
                && view.bindings().reportSha256().equals(candidate.reportSha256())
                && candidate.documentVersionId() == null && candidate.contentSha256() == null
                && candidate.fragmentIndex() == null && candidate.fragmentSha256() == null);
            EvidenceView actual = findings.evidence(view.projectId(), view.bindings().scanJobId(), evidenceId);
            require(actual != null && evidenceId.equals(actual.id()) && view.projectId().equals(actual.projectId())
                && view.bindings().scanJobId().equals(actual.jobId()) && actual.kind() != null
                && Objects.equals(actual.kind(), input.kind())
                // The approved minimal projection deliberately uses kind, not the raw scanner summary.
                && Objects.equals(actual.kind(), input.summary()) && Objects.equals(actual.kind(), candidate.label())
                && Objects.equals(actual.location() == null ? null : actual.location().startLine(), input.startLine())
                && Objects.equals(actual.location() == null ? null : actual.location().endLine(), input.endLine()));
        }
    }

    /** 通过文档公开接口取不可变版本，再核对实际 UTF-8 大小与 SHA-256，不仅信任数据库中的摘要字段。 */
    private Map<UUID, DocumentVersionView> versions(AgentRequestView view) {
        List<AgentRequestView.DocumentVersionBinding> bindings = view.bindings().documentVersions();
        require(bindings != null && bindings.size() <= 10);
        Map<UUID, DocumentVersionView> versions = new LinkedHashMap<>();
        for (AgentRequestView.DocumentVersionBinding binding : bindings) {
            require(binding != null && binding.documentVersionId() != null && hash(binding.contentSha256())
                && !versions.containsKey(binding.documentVersionId()));
            DocumentVersionView actual = documents.getVersionById(view.projectId(), binding.documentVersionId());
            require(actual != null && binding.documentVersionId().equals(actual.id())
                && view.projectId().equals(actual.projectId())
                && binding.contentSha256().equals(actual.contentSha256())
                && actual.content() != null && actual.content().length() <= 262144
                && actual.fragmentCount() >= 1 && actual.fragmentCount() <= 256);
            byte[] bytes = actual.content().getBytes(StandardCharsets.UTF_8);
            require(bytes.length <= 262144 && bytes.length == actual.byteSize()
                && binding.contentSha256().equals(sha256(bytes)));
            versions.put(actual.id(), actual);
        }
        return versions;
    }

    /** 目前无公开精确片段读取接口，故重放原有界检索并比对版本正文、偏移和摘要；无法重现的快照拒绝。 */
    private Map<String, DocumentFragmentView> replayFragments(UUID projectId, List<FindingView> selected,
            Map<UUID, DocumentVersionView> versions) {
        Map<String, DocumentFragmentView> accepted = new LinkedHashMap<>();
        if (versions.isEmpty()) return accepted;
        List<UUID> ids = versions.keySet().stream().sorted().toList();
        for (FindingView finding : selected) {
            require(finding.ruleId() != null && finding.message() != null);
            String querySource = finding.ruleId().length() >= 2 ? finding.ruleId() : finding.message();
            String query = querySource.substring(0, Math.min(120, querySource.length()));
            List<DocumentFragmentView> found = documents.search(projectId, ids, query);
            require(found != null && found.size() <= 4);
            int totalCharacters = 0;
            for (DocumentFragmentView fragment : found) {
                require(fragment != null && projectId.equals(fragment.projectId())
                    && fragment.content() != null && fragment.content().length() <= 1024);
                totalCharacters += fragment.content().length();
                require(totalCharacters <= 4096);
                DocumentVersionView version = versions.get(fragment.documentVersionId());
                require(version != null && version.contentSha256().equals(fragment.contentSha256())
                    && fragment.fragmentIndex() >= 0 && fragment.fragmentIndex() < version.fragmentCount()
                    && fragment.startOffset() >= 0 && fragment.startOffset() <= version.content().length()
                    && fragment.content().length() <= version.content().length() - fragment.startOffset()
                    && version.content().regionMatches(fragment.startOffset(), fragment.content(), 0,
                        fragment.content().length()) && hash(fragment.fragmentSha256())
                    && fragment.fragmentSha256().equals(sha256(fragment.content().getBytes(StandardCharsets.UTF_8))));
            }
            for (DocumentFragmentView fragment : found) {
                String identity = fragmentIdentity(fragment.documentVersionId(), fragment.fragmentIndex());
                DocumentFragmentView previous = accepted.get(identity);
                require(previous == null || previous.equals(fragment));
                accepted.putIfAbsent(identity, fragment);
                if (accepted.size() == 10) break;
            }
            if (accepted.size() == 10) break;
        }
        return accepted;
    }

    private static Map<String, AgentSnapshot.Candidate> candidateIndex(List<AgentSnapshot.Candidate> values,
            UUID projectId) {
        Map<String, AgentSnapshot.Candidate> result = new HashMap<>();
        for (AgentSnapshot.Candidate candidate : values) {
            require(candidate != null && alias(candidate.citationId()) && projectId.equals(candidate.projectId())
                && result.putIfAbsent(candidate.citationId(), candidate) == null);
        }
        return result;
    }

    private static AgentSnapshot.Candidate candidate(Map<String, AgentSnapshot.Candidate> candidates,
            Set<String> used, String id) {
        require(alias(id) && used.add(id));
        AgentSnapshot.Candidate candidate = candidates.get(id);
        require(candidate != null);
        return candidate;
    }

    private static String fragmentIdentity(UUID version, int index) { return version + ":" + index; }
    private static boolean alias(String value) {
        return value != null && value.matches("c-[A-Za-z0-9-]{1,120}");
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static void require(boolean valid) {
        if (!valid) throw new AgentEnablementInvalidStateException();
    }
}
