package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.UUID;

public record ReportSubmissionView(UUID id, UUID projectId, UUID repositoryId, UUID ruleSetVersionId,
                                   GitRevision revision, PullRequestRef pullRequest, String reportSha256,
                                   String requestDigest, ReportSubmissionStatus status,
                                   UUID gateEvaluationId, Instant createdAt) { }
