package io.github.aiarchguard.platform.project;

import java.util.UUID;

public interface ProjectOperations {
    ProjectView create(String key, String name);
    ProjectView get(UUID projectId);
    ProjectPage list(int page, int size);
    ProjectView update(UUID projectId, String name, long expectedVersion);
    void delete(UUID projectId, long expectedVersion);
    ProjectMemberPage listMembers(UUID projectId, int page, int size);
    ProjectMemberView setMember(UUID projectId, UUID memberActorId, ProjectMemberRole role);
    void removeMember(UUID projectId, UUID memberActorId);
}
