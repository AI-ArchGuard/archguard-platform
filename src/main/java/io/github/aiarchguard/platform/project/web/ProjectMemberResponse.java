package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectMemberRole;
import io.github.aiarchguard.platform.project.ProjectMemberView;
import java.time.Instant;
import java.util.UUID;

/** 项目内的一条成员关系；createdAt 是关系创建时间，不是账号注册时间，也不包含账号凭据。 */
record ProjectMemberResponse(UUID actorId, ProjectMemberRole role, Instant createdAt) {
    static ProjectMemberResponse from(ProjectMemberView view) {
        return new ProjectMemberResponse(view.actorId(), view.role(), view.createdAt());
    }
}
