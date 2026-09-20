package io.github.aiarchguard.platform.ruleset.internal;

import io.github.aiarchguard.platform.ruleset.RuleSetVersionView;
import io.github.aiarchguard.platform.ruleset.RuleSetView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleSetStore {
    void insert(RuleSetView ruleSet);
    List<RuleSetView> list(UUID projectId, UUID repositoryId);
    Optional<RuleSetView> find(UUID projectId, UUID repositoryId, UUID ruleSetId);
    int nextVersion(UUID ruleSetId);
    void insertVersion(RuleSetVersionView version);
    List<RuleSetVersionView> listVersions(UUID ruleSetId);
    Optional<RuleSetVersionView> findVersion(UUID projectId, UUID repositoryId, UUID versionId);
    boolean existsForProject(UUID projectId);
}
