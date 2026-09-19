package io.github.aiarchguard.platform.scanjob.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class ScanJobProjectDeletionGuard implements ProjectDeletionGuard {
    private final ScanJobStore store;
    ScanJobProjectDeletionGuard(ScanJobStore store){this.store=store;}
    @Override public boolean hasContent(UUID projectId){return store.existsForProject(projectId);}
}
