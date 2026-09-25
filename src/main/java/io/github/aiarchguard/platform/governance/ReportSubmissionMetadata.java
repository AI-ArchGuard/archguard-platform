package io.github.aiarchguard.platform.governance;

import java.util.UUID;

public record ReportSubmissionMetadata(UUID ruleSetVersionId, GitRevision revision,
                                       PullRequestRef pullRequest, String scannerVersion,
                                       String schemaVersion, String reportSha256) { }
