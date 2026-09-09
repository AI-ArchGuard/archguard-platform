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
}
