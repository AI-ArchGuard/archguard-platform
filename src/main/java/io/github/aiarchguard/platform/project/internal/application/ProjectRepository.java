package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.Project;
import io.github.aiarchguard.platform.project.internal.domain.ProjectMember;
import io.github.aiarchguard.platform.project.internal.domain.ProjectName;
import io.github.aiarchguard.platform.project.internal.domain.ProjectRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    void insertWithMaintainer(Project project);
    Optional<Project> findForActor(UUID projectId, UUID actorId);
    Optional<ProjectAccess> lockForActor(UUID projectId, UUID actorId);
    List<Project> findPageForActor(UUID actorId, int limit, long offset);
    long countForActor(UUID actorId);
    int updateName(UUID projectId, ProjectName name, long expectedVersion);
    int delete(UUID projectId, long expectedVersion);
    List<ProjectMember> findMemberPage(UUID projectId, int limit, long offset);
    long countMembers(UUID projectId);
    Optional<ProjectMember> findMember(UUID projectId, UUID actorId);
    void upsertMember(UUID projectId, UUID actorId, ProjectRole role, Instant createdAt);
    int deleteMember(UUID projectId, UUID actorId);
    long countMaintainers(UUID projectId);
}
