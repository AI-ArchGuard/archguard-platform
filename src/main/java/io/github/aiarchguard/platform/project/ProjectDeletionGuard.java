package io.github.aiarchguard.platform.project;

import java.util.UUID;

public interface ProjectDeletionGuard {
    boolean hasContent(UUID projectId);
}
