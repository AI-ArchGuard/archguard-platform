package io.github.aiarchguard.platform.governance;

import java.util.List;
import java.util.UUID;

public interface BaselineOperations {
    BaselineVersionView promote(UUID projectId, UUID repositoryId, String targetBranch,
                                UUID ruleSetVersionId, UUID scanJobId, String commitSha);
    BaselineVersionView select(UUID projectId, UUID repositoryId, String targetBranch,
                               UUID ruleSetVersionId, UUID baselineVersionId);
    BaselineVersionView active(UUID projectId, UUID repositoryId, String targetBranch, UUID ruleSetVersionId);
    List<BaselineVersionView> list(UUID projectId, UUID repositoryId, String targetBranch, UUID ruleSetVersionId);
    ComparisonView compare(UUID projectId, UUID repositoryId, String targetBranch,
                           UUID ruleSetVersionId, UUID candidateJobId);
}
