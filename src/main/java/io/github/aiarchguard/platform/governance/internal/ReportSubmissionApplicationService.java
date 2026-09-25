package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GateEvaluationOperations;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GithubLinkNotFoundException;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.GovernanceScopeInput;
import io.github.aiarchguard.platform.governance.ReportSubmissionMetadata;
import io.github.aiarchguard.platform.governance.ReportSubmissionNotFoundException;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionStatus;
import io.github.aiarchguard.platform.governance.ReportSubmissionView;
import io.github.aiarchguard.platform.governance.SubmissionGateView;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class ReportSubmissionApplicationService implements ReportSubmissionOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final GithubRepositoryStore links;
    private final ReportSubmissionStore store;
    private final ReportSubmissionWriteService writer;
    private final ReportSubmissionFinalizeService finalizer;
    private final GateEvaluationOperations gates;
    private final CurrentActorProvider actors;

    ReportSubmissionApplicationService(ProjectAuthorization projects, RepositoryOperations repositories,
            RuleSetCatalog ruleSets, GithubRepositoryStore links, ReportSubmissionStore store,
            ReportSubmissionWriteService writer, ReportSubmissionFinalizeService finalizer,
            GateEvaluationOperations gates, CurrentActorProvider actors) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets;
        this.links = links; this.store = store; this.writer = writer; this.finalizer = finalizer;
        this.gates = gates; this.actors = actors;
    }

    @Override public Acceptance submit(UUID projectId, UUID repositoryId, String idempotencyKey,
            ReportSubmissionMetadata rawMetadata, byte[] report) {
        projects.requireMaintainer(projectId);
        String key = GovernanceScopeInput.idempotencyKey(idempotencyKey);
        repositories.get(projectId, repositoryId);
        ReportSubmissionMetadata metadata = SubmissionContract.validate(rawMetadata, report);
        ruleSets.requireVersion(projectId, repositoryId, metadata.ruleSetVersionId());
        var link = links.find(projectId, repositoryId).orElseThrow(GithubLinkNotFoundException::new);
        if (!link.providerRepositoryId().equals(metadata.revision().providerRepositoryId())) {
            throw new GovernanceConflictException("Report GitHub repository identity does not match the registered Repository");
        }
        String digest = SubmissionContract.digest(projectId, repositoryId, metadata);
        var byKey = store.byKey(projectId, key);
        if (byKey.isPresent() && !byKey.get().view().requestDigest().equals(digest)) {
            throw new GovernanceConflictException("Idempotency-Key is bound to different report inputs");
        }
        ReportSubmissionStore.Stored existing = byKey.or(() -> store.byDigest(projectId, digest)).orElse(null);
        Acceptance accepted = existing == null
            ? writer.accept(projectId, repositoryId, key, digest, metadata, report)
            : new Acceptance(existing.view(), true);
        ReportSubmissionView submitted = accepted.submission();
        if (submitted.status() == ReportSubmissionStatus.RECEIVED) {
            ReportSubmissionStore.Stored stored = store.find(projectId, repositoryId, submitted.id()).orElseThrow();
            GateEvaluationView gate = gates.evaluate(projectId, repositoryId, submitted.revision().targetBranch(),
                submitted.ruleSetVersionId(), stored.scanJobId(), "submission:" + submitted.id());
            finalizer.complete(stored, gate, actors.currentActor().id());
            submitted = store.find(projectId, repositoryId, submitted.id()).orElseThrow().view();
        }
        return new Acceptance(submitted, accepted.replay());
    }

    @Override public ReportSubmissionView get(UUID projectId, UUID repositoryId, UUID submissionId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return store.find(projectId, repositoryId, submissionId)
            .orElseThrow(ReportSubmissionNotFoundException::new).view();
    }
    @Override public SubmissionGateView gate(UUID projectId, UUID repositoryId, UUID submissionId) {
        ReportSubmissionView submission = get(projectId, repositoryId, submissionId);
        if (submission.status() != ReportSubmissionStatus.COMPLETED || submission.gateEvaluationId() == null) {
            throw new GovernanceConflictException("Report submission has not completed gate evaluation");
        }
        GateEvaluationView value = gates.get(projectId, repositoryId, submission.gateEvaluationId());
        return new SubmissionGateView(value.id(), submissionId, projectId, repositoryId,
            submission.ruleSetVersionId(), submission.revision(), value.baselineVersionId(),
            value.outcome(), value.errorKind(), value.errorCode(), value.ciExitCode(),
            value.policyVersion(), value.fingerprintVersion(),
            Map.of("NEW", value.newCount(), "EXISTING", value.existingCount(), "RESOLVED", value.resolvedCount()),
            value.matchedExceptionVersionIds(), value.evaluatedAt());
    }
}
