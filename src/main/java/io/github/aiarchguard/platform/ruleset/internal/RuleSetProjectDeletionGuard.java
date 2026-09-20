package io.github.aiarchguard.platform.ruleset.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class RuleSetProjectDeletionGuard implements ProjectDeletionGuard {
    private final RuleSetStore store;
    RuleSetProjectDeletionGuard(RuleSetStore store){this.store=store;}
    @Override public boolean hasContent(UUID projectId){return store.existsForProject(projectId);}
}
