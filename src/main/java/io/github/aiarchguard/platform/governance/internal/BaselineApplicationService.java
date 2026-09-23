package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.governance.BaselineNotFoundException;
import io.github.aiarchguard.platform.governance.BaselineOperations;
import io.github.aiarchguard.platform.governance.BaselineVersionView;
import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.repository.RepositoryView;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobResult;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import io.github.aiarchguard.platform.scanjob.ScanJobView;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BaselineApplicationService implements BaselineOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final ScanJobOperations jobs;
    private final CurrentActorProvider actors;
    private final ReportFingerprintComputer fingerprints;
    private final BaselineStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;

    BaselineApplicationService(ProjectAuthorization projects, RepositoryOperations repositories,
            RuleSetCatalog ruleSets, ScanJobOperations jobs, CurrentActorProvider actors,
            ReportFingerprintComputer fingerprints, BaselineStore store, AuditRecorder audit,
            TraceIdProvider traceIds, Clock clock) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets;
        this.jobs = jobs; this.actors = actors; this.fingerprints = fingerprints; this.store = store;
        this.audit = audit; this.traceIds = traceIds; this.clock = clock;
    }

    @Override @Transactional
    public BaselineVersionView promote(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId, UUID scanJobId, String commitSha) {
        projects.requireMaintainer(projectId);
        String branch = branch(targetBranch);
        String commit = commit(commitSha);
        RepositoryView repository = repositoryAndRules(projectId, repositoryId, ruleSetVersionId);
        ScanJobResult report = successfulJob(projectId, repositoryId, ruleSetVersionId, scanJobId);
        List<FindingSnapshot> snapshots = fingerprints.compute(report.canonicalJson(), repository.scannerIdentity());
        BaselineScope scope = store.lockOrCreate(projectId, repositoryId, branch, ruleSetVersionId);
        BaselineVersionView existing = store.byJob(scope.id(), scanJobId).orElse(null);
        UUID actorId = actors.currentActor().id();
        Instant now = Instant.now(clock);
        BaselineVersionView version;
        if (existing != null) {
            if (!existing.commitSha().equals(commit) || !existing.reportSha256().equals(report.sha256())) {
                throw new GovernanceConflictException("The scan job is already bound to different baseline metadata");
            }
            version = existing;
        } else {
            version = store.insertVersion(scope, scanJobId, commit, report.sha256(),
                ReportFingerprintComputer.VERSION, actorId, now, snapshots);
        }
        BaselineVersionView selected = store.select(scope, version.id(), actorId, now);
        record(actorId, projectId, "governance.baseline.promote", Map.of("baselineVersionId", selected.id(),
            "scanJobId", scanJobId, "selectionVersion", selected.selectionVersion()));
        return selected;
    }

    @Override @Transactional
    public BaselineVersionView select(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId, UUID baselineVersionId) {
        projects.requireMaintainer(projectId);
        String branch = branch(targetBranch);
        repositoryAndRules(projectId, repositoryId, ruleSetVersionId);
        BaselineScope scope = store.scope(projectId, repositoryId, branch, ruleSetVersionId)
            .orElseThrow(BaselineNotFoundException::new);
        if (store.version(scope.id(), baselineVersionId).isEmpty()) throw new BaselineNotFoundException();
        scope = store.lockOrCreate(projectId, repositoryId, branch, ruleSetVersionId);
        UUID actorId = actors.currentActor().id();
        BaselineVersionView selected = store.select(scope, baselineVersionId, actorId, Instant.now(clock));
        record(actorId, projectId, "governance.baseline.select", Map.of("baselineVersionId", selected.id(),
            "selectionVersion", selected.selectionVersion()));
        return selected;
    }

    @Override public BaselineVersionView active(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId) {
        projects.requireViewer(projectId);
        String branch = branch(targetBranch);
        repositoryAndRules(projectId, repositoryId, ruleSetVersionId);
        BaselineScope scope = store.scope(projectId, repositoryId, branch, ruleSetVersionId)
            .orElseThrow(BaselineNotFoundException::new);
        if (scope.activeVersionId() == null) throw new BaselineNotFoundException();
        return store.version(scope.id(), scope.activeVersionId()).orElseThrow(BaselineNotFoundException::new);
    }

    @Override public List<BaselineVersionView> list(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId) {
        projects.requireViewer(projectId);
        String branch = branch(targetBranch);
        repositoryAndRules(projectId, repositoryId, ruleSetVersionId);
        return store.scope(projectId, repositoryId, branch, ruleSetVersionId)
            .map(scope -> store.versions(scope.id())).orElseGet(List::of);
    }

    @Override @Transactional
    public ComparisonView compare(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId, UUID candidateJobId) {
        projects.requireMaintainer(projectId);
        String branch = branch(targetBranch);
        RepositoryView repository = repositoryAndRules(projectId, repositoryId, ruleSetVersionId);
        ScanJobResult report = successfulJob(projectId, repositoryId, ruleSetVersionId, candidateJobId);
        if (store.scope(projectId, repositoryId, branch, ruleSetVersionId).isEmpty()) {
            throw new BaselineNotFoundException();
        }
        BaselineScope scope = store.lockOrCreate(projectId, repositoryId, branch, ruleSetVersionId);
        if (scope.activeVersionId() == null) throw new BaselineNotFoundException();
        BaselineVersionView baseline = store.version(scope.id(), scope.activeVersionId())
            .orElseThrow(BaselineNotFoundException::new);
        if (!ReportFingerprintComputer.VERSION.equals(baseline.fingerprintVersion())) {
            throw new GovernanceConflictException("The active baseline uses an incompatible fingerprint version");
        }
        ComparisonView existing = store.comparison(baseline.id(), candidateJobId).orElse(null);
        if (existing != null) {
            if (!existing.candidateReportSha256().equals(report.sha256())) {
                throw new GovernanceConflictException("Candidate report changed after comparison");
            }
            return existing;
        }
        List<FindingSnapshot> candidate = fingerprints.compute(report.canonicalJson(), repository.scannerIdentity());
        var classified = FindingClassifier.classify(store.findings(baseline.id()), candidate);
        ComparisonView comparison = store.insertComparison(baseline.id(), candidateJobId, report.sha256(),
            ReportFingerprintComputer.VERSION, Instant.now(clock), classified);
        record(actors.currentActor().id(), projectId, "governance.comparison.create", Map.of(
            "comparisonId", comparison.id(), "baselineVersionId", baseline.id(), "candidateJobId", candidateJobId));
        return comparison;
    }

    private RepositoryView repositoryAndRules(UUID projectId, UUID repositoryId, UUID versionId) {
        RepositoryView repository = repositories.get(projectId, repositoryId);
        ruleSets.requireVersion(projectId, repositoryId, versionId);
        return repository;
    }
    private ScanJobResult successfulJob(UUID projectId, UUID repositoryId, UUID versionId, UUID jobId) {
        ScanJobView job = jobs.get(projectId, jobId);
        if (!job.repositoryId().equals(repositoryId) || !job.ruleSetVersionId().equals(versionId)) {
            throw new GovernanceConflictException("Scan job belongs to another repository or RuleSetVersion");
        }
        if (job.status() != ScanJobStatus.SUCCEEDED) {
            throw new GovernanceConflictException("Only successful scans can be used for governance");
        }
        return jobs.result(projectId, jobId);
    }
    private void record(UUID actorId, UUID projectId, String action, Map<String, Object> metadata) {
        audit.record(new AuditEvent(actorId, projectId, action, AuditResult.SUCCESS,
            traceIds.currentTraceId(), metadata));
    }
    private static String branch(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || !value.equals(value.trim())
            || value.startsWith("refs/heads/") || value.startsWith("/") || value.endsWith("/")
            || value.contains("..") || value.contains("@{") || value.contains("//")
            || value.codePoints().anyMatch(code -> Character.isISOControl(code)
                || " ~^:?*[]\\".indexOf(code) >= 0)) {
            throw new InvalidGovernanceInputException("Target branch is invalid");
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
    private static String commit(String value) {
        if (value == null || !value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new InvalidGovernanceInputException("Commit SHA must be 40 or 64 lowercase hex characters");
        }
        return value;
    }
}
