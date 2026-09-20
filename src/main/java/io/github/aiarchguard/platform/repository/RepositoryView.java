package io.github.aiarchguard.platform.repository;

import java.time.Instant;
import java.util.UUID;

public record RepositoryView(UUID id, UUID projectId, String key, String name, String mountPath,
                             String scannerIdentity, UUID createdBy, Instant createdAt, long version) {
}
