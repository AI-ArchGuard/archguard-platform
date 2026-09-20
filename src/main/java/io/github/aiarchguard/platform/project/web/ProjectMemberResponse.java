package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectMemberRole;
import io.github.aiarchguard.platform.project.ProjectMemberView;
import java.time.Instant;
import java.util.UUID;

record ProjectMemberResponse(UUID actorId, ProjectMemberRole role, Instant createdAt) {
    static ProjectMemberResponse from(ProjectMemberView view) {
        return new ProjectMemberResponse(view.actorId(), view.role(), view.createdAt());
    }
}
