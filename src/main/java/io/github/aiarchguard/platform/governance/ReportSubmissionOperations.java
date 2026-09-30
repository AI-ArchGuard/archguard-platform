package io.github.aiarchguard.platform.governance;

import java.util.UUID;

public interface ReportSubmissionOperations {
    record Acceptance(ReportSubmissionView submission, boolean replay) { }
    Acceptance submit(UUID projectId, UUID repositoryId, String idempotencyKey,
                      ReportSubmissionMetadata metadata, byte[] report);
    ReportSubmissionView get(UUID projectId, UUID repositoryId, UUID submissionId);
    SubmissionGateView gate(UUID projectId, UUID repositoryId, UUID submissionId);
    /** Only an applied PR head paired with this completed scan report can back an Agent summary. */
    boolean matchesCompletedPrRevision(UUID projectId, UUID scanJobId, String reportSha256,
                                       UUID prHeadRevisionId);
    boolean matchesCompletedPrRevisionForActor(UUID projectId, UUID actorId, UUID scanJobId,
                                               String reportSha256, UUID prHeadRevisionId);
}
