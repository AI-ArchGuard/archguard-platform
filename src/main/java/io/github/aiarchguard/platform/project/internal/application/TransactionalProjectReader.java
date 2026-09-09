package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.Project;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionalProjectReader {
    private final ProjectRepository repository;

    public TransactionalProjectReader(ProjectRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<Project> findForActor(UUID projectId, UUID actorId) {
        return repository.findForActor(projectId, actorId);
    }

    @Transactional(readOnly = true)
    public ProjectPageData listForActor(UUID actorId, int page, int size) {
        return new ProjectPageData(
            repository.findPageForActor(actorId, size, (long) page * size),
            repository.countForActor(actorId));
    }

    @Transactional(readOnly = true)
    public Optional<ProjectMemberPageData> listMembersForActor(UUID projectId, UUID actorId, int page, int size) {
        if (repository.findForActor(projectId, actorId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProjectMemberPageData(
            repository.findMemberPage(projectId, size, (long) page * size),
            repository.countMembers(projectId)));
    }
}
