package io.github.aiarchguard.platform.project.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Project(ProjectId id, ProjectKey key, ProjectName name, UUID createdBy, Instant createdAt,
                      long version) {
    public Project {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (version < 0) {
            throw new IllegalArgumentException("Project version must not be negative");
        }
    }
}
