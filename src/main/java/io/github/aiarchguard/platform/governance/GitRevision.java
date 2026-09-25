package io.github.aiarchguard.platform.governance;

public record GitRevision(String provider, String providerRepositoryId, String commitSha,
                          String targetBranch) { }
