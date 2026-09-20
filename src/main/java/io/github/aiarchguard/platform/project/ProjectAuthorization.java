package io.github.aiarchguard.platform.project;

import java.util.UUID;

public interface ProjectAuthorization {
    ProjectAccessView requireViewer(UUID projectId);
    ProjectAccessView requireMaintainer(UUID projectId);
}
