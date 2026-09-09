package io.github.aiarchguard.platform.project;

import java.util.UUID;

public interface ProjectOperations {
    ProjectView create(String key, String name);
    ProjectView get(UUID projectId);
}
