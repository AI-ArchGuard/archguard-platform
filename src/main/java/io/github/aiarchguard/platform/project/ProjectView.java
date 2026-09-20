package io.github.aiarchguard.platform.project;

import java.time.Instant;
import java.util.UUID;

public record ProjectView(UUID id, String key, String name, UUID createdBy, Instant createdAt, long version) {}
