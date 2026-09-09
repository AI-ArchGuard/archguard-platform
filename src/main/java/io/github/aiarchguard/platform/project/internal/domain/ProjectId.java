package io.github.aiarchguard.platform.project.internal.domain;

import java.util.Objects;
import java.util.UUID;

public record ProjectId(UUID value) {
    public ProjectId {
        Objects.requireNonNull(value, "value");
    }
}
