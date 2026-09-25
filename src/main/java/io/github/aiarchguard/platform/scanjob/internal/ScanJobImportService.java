package io.github.aiarchguard.platform.scanjob.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.finding.AcceptedResult;
import io.github.aiarchguard.platform.finding.ResultAcceptance;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import io.github.aiarchguard.platform.scanjob.ScanJobImportOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobOutcome;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ScanJobImportService implements ScanJobImportOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final CurrentActorProvider actors;
    private final ScanJobStore jobs;
    private final ResultAcceptance results;
    private final ObjectMapper mapper;
    private final Clock clock;

    ScanJobImportService(ProjectAuthorization projects, RepositoryOperations repositories,
            RuleSetCatalog ruleSets, CurrentActorProvider actors, ScanJobStore jobs,
            ResultAcceptance results, ObjectMapper mapper, Clock clock) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets;
        this.actors = actors; this.jobs = jobs; this.results = results; this.mapper = mapper; this.clock = clock;
    }

    @Override @Transactional
    public void importCompleted(UUID projectId, UUID repositoryId, UUID ruleSetVersionId,
            UUID jobId, String scannerVersion, byte[] report) {
        projects.requireMaintainer(projectId);
        var repository = repositories.get(projectId, repositoryId);
        ruleSets.requireVersion(projectId, repositoryId, ruleSetVersionId);
        Instant now = Instant.now(clock);
        UUID actor = actors.currentActor().id();
        jobs.insert(new ScanJobRecord(jobId, projectId, repositoryId, ruleSetVersionId,
            "ci:" + jobId, "0".repeat(64), ScanJobStatus.QUEUED, null, actor, now, null, null,
            null, 0, null, 0, null, null, null, null, false, null, null));
        AcceptedResult accepted = results.validateAndStore(projectId, jobId, repository.scannerIdentity(),
            scannerVersion, report);
        ScanJobOutcome outcome;
        try {
            outcome = mapper.readTree(report).path("findings").isEmpty()
                ? ScanJobOutcome.PASS : ScanJobOutcome.VIOLATION;
        } catch (IOException exception) {
            throw new IllegalStateException("Validated Scanner report cannot be parsed", exception);
        }
        if (!jobs.completeImported(jobId, report, accepted.sha256(), accepted.scannerVersion(),
                accepted.schemaVersion(), outcome, Instant.now(clock))) {
            throw new IllegalStateException("CI scan result could not be committed");
        }
    }
}
