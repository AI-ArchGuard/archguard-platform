package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.Project;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    void insertWithMaintainer(Project project);
    Optional<Project> findForActor(UUID projectId, UUID actorId);
}
