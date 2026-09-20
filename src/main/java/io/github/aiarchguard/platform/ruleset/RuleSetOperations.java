package io.github.aiarchguard.platform.ruleset;

import java.util.List;
import java.util.UUID;

public interface RuleSetOperations {
    RuleSetView create(UUID projectId, UUID repositoryId, String key, String name);
    List<RuleSetView> list(UUID projectId, UUID repositoryId);
    RuleSetVersionView createVersion(UUID projectId, UUID repositoryId, UUID ruleSetId, String yaml);
    List<RuleSetVersionView> listVersions(UUID projectId, UUID repositoryId, UUID ruleSetId);
}
