package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.UUID;

public record GithubPullRequestView(UUID projectId, UUID repositoryId, String externalId,
                                    String headSha, String baseSha, String targetBranch,
                                    Instant eventAt, UUID currentGateEvaluationId) { }
