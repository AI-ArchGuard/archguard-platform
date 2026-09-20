package io.github.aiarchguard.platform.project;

import java.time.Instant;
import java.util.UUID;

public record ProjectMemberView(UUID actorId, ProjectMemberRole role, Instant createdAt) {}
