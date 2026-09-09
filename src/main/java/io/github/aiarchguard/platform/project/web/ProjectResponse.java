package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectView;
import java.time.Instant;
import java.util.UUID;

record ProjectResponse(UUID id, String key, String name, UUID createdBy, Instant createdAt) {
    static ProjectResponse from(ProjectView view) {
        return new ProjectResponse(view.id(), view.key(), view.name(), view.createdBy(), view.createdAt());
    }
}
