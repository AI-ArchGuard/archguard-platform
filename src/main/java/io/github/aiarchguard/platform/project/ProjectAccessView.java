package io.github.aiarchguard.platform.project;

import java.util.UUID;

public record ProjectAccessView(UUID projectId, String key, String name, ProjectMemberRole role) {}
