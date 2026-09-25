package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.BaselineNotFoundException;
import io.github.aiarchguard.platform.governance.BaselineOperations;
import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.GateErrorKind;
import io.github.aiarchguard.platform.governance.GateEvaluationNotFoundException;
import io.github.aiarchguard.platform.governance.GateEvaluationOperations;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GateOutcome;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.GovernanceScopeInput;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobConflictException;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class GateEvaluationApplicationService implements GateEvaluationOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final ScanJobOperations jobs;
    private final BaselineOperations baselines;
    private final PolicyExceptionStore exceptions;
    private final GateEvaluationStore store;
    private final GateWriteService writer;
    private final CurrentActorProvider actors;
    private final Clock clock;

    GateEvaluationApplicationService(ProjectAuthorization projects, RepositoryOperations repositories,
            RuleSetCatalog ruleSets, ScanJobOperations jobs, BaselineOperations baselines,
            PolicyExceptionStore exceptions, GateEvaluationStore store, GateWriteService writer,
            CurrentActorProvider actors, Clock clock) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets;
        this.jobs = jobs; this.baselines = baselines; this.exceptions = exceptions; this.store = store;
        this.writer = writer; this.actors = actors; this.clock = clock;
    }

    @Override public GateEvaluationView evaluate(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId, UUID candidateJobId, String idempotencyKey) {
        projects.requireMaintainer(projectId);
        String branch = GovernanceScopeInput.branch(targetBranch);
        String key = GovernanceScopeInput.idempotencyKey(idempotencyKey);
        repositories.get(projectId, repositoryId);
        ruleSets.requireVersion(projectId, repositoryId, ruleSetVersionId);
        var job = jobs.get(projectId, candidateJobId);
        if (!job.repositoryId().equals(repositoryId) || !job.ruleSetVersionId().equals(ruleSetVersionId)) {
            throw new GovernanceConflictException("Scan job belongs to another repository or RuleSetVersion");
        }
        String digest = digest(projectId, repositoryId, branch, ruleSetVersionId, candidateJobId);
        var previous = store.byKey(projectId, key);
        if (previous.isPresent()) {
            if (!previous.get().requestSha256().equals(digest)) {
                throw new GovernanceConflictException("Idempotency-Key is bound to different gate inputs");
            }
            return previous.get().view();
        }
        UUID actor = actors.currentActor().id();
        Instant at = Instant.now(clock);
        GateEvaluationView evaluation;
        if (job.status() != ScanJobStatus.SUCCEEDED) {
            evaluation = error(projectId, repositoryId, branch, ruleSetVersionId, candidateJobId,
                null, null, GateErrorKind.EXECUTION_OR_CONTRACT, "scan_job_not_successful", 70, at);
        } else {
            try {
                ComparisonView comparison = baselines.compare(projectId, repositoryId, branch,
                    ruleSetVersionId, candidateJobId);
                Instant decisionAt = Instant.now(clock);
                GateDecision decision = GatePolicy.evaluate(comparison,
                    exceptions.list(projectId, repositoryId, branch, ruleSetVersionId), decisionAt);
                evaluation = new GateEvaluationView(UUID.randomUUID(), projectId, repositoryId, branch,
                    ruleSetVersionId, candidateJobId, comparison.id(), comparison.baselineVersionId(), decision.outcome(),
                    decision.ciExitCode(), null, null, GatePolicy.VERSION, comparison.fingerprintVersion(),
                    comparison.newCount(), comparison.existingCount(), comparison.resolvedCount(),
                    decision.blockedCount(), decision.matchedExceptionVersionIds(), decisionAt);
            } catch (BaselineNotFoundException ex) {
                evaluation = error(projectId, repositoryId, branch, ruleSetVersionId, candidateJobId,
                    null, null, GateErrorKind.CONFIGURATION, "baseline_not_found", 64, at);
            } catch (InvalidGovernanceReportException | GovernanceConflictException | ScanJobConflictException ex) {
                evaluation = error(projectId, repositoryId, branch, ruleSetVersionId, candidateJobId,
                    null, null, GateErrorKind.EXECUTION_OR_CONTRACT, "comparison_failed", 70, at);
            }
        }
        return writer.persist(evaluation, key, digest, actor);
    }

    @Override public GateEvaluationView get(UUID projectId, UUID repositoryId, UUID evaluationId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return store.find(projectId, repositoryId, evaluationId)
            .orElseThrow(GateEvaluationNotFoundException::new);
    }

    private static GateEvaluationView error(UUID projectId, UUID repositoryId, String branch, UUID rules, UUID job,
            UUID comparison, UUID baseline, GateErrorKind kind, String code, int exitCode, Instant at) {
        return new GateEvaluationView(UUID.randomUUID(), projectId, repositoryId, branch, rules, job,
            comparison, baseline, GateOutcome.ERROR, exitCode, kind, code, GatePolicy.VERSION,
            ReportFingerprintComputer.VERSION, 0, 0, 0, 0, List.of(), at);
    }
    private static String digest(UUID project, UUID repository, String branch, UUID rules, UUID job) {
        try {
            byte[] bytes = (project + "\n" + repository + "\n" + branch + "\n" + rules + "\n" + job)
                .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
