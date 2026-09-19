package io.github.aiarchguard.platform.repository.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.InvalidRepositoryException;
import io.github.aiarchguard.platform.repository.RepositoryConflictException;
import io.github.aiarchguard.platform.repository.RepositoryCatalog;
import io.github.aiarchguard.platform.repository.RepositoryNotFoundException;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.repository.RepositoryView;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
final class RepositoryApplicationService implements RepositoryOperations, RepositoryCatalog {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{2,62}");

    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final RepositoryStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;
    private final Path sourceRoot;

    RepositoryApplicationService(ProjectAuthorization projects, CurrentActorProvider actors,
                                 RepositoryStore store, AuditRecorder audit, TraceIdProvider traceIds,
                                 Clock clock, @Value("${archguard.repositories.source-root:./sources}") String sourceRoot) {
        this.projects = projects;
        this.actors = actors;
        this.store = store;
        this.audit = audit;
        this.traceIds = traceIds;
        this.clock = clock;
        this.sourceRoot = Path.of(sourceRoot).toAbsolutePath().normalize();
    }

    @Override
    public RepositoryView create(UUID projectId, String key, String name, String mountPath) {
        projects.requireMaintainer(projectId);
        validateKeyAndName(key, name);
        String normalizedPath = ControlledRepositoryPath.normalize(sourceRoot, mountPath);
        UUID actorId = actors.currentActor().id();
        UUID repositoryId = UUID.randomUUID();
        RepositoryView view = new RepositoryView(repositoryId, projectId, key, name.trim(), normalizedPath,
            "platform:" + projectId + ":" + repositoryId, actorId, Instant.now(clock), 0);
        try {
            store.insert(view);
        } catch (DuplicateKeyException exception) {
            throw new RepositoryConflictException();
        }
        audit.record(new AuditEvent(actorId, projectId, "repository.create", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("repositoryId", view.id(), "mountPath", normalizedPath)));
        return view;
    }

    @Override
    public List<RepositoryView> list(UUID projectId) {
        projects.requireViewer(projectId);
        return store.list(projectId);
    }

    @Override
    public RepositoryView get(UUID projectId, UUID repositoryId) {
        projects.requireViewer(projectId);
        return requireRegistered(projectId, repositoryId);
    }

    @Override
    public RepositoryView requireRegistered(UUID projectId, UUID repositoryId) {
        return store.find(projectId, repositoryId).orElseThrow(RepositoryNotFoundException::new);
    }

    private static void validateKeyAndName(String key, String name) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidRepositoryException("Repository key must match [a-z][a-z0-9-]{2,62}");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new InvalidRepositoryException("Repository name must contain between 1 and 120 characters");
        }
    }
}
