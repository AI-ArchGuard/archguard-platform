package io.github.aiarchguard.platform.project.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectMember(UUID actorId, ProjectRole role, Instant createdAt) {
    public ProjectMember {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
