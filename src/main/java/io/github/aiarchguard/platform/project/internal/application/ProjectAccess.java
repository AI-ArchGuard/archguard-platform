package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.Project;
import io.github.aiarchguard.platform.project.internal.domain.ProjectRole;

public record ProjectAccess(Project project, ProjectRole role) {}
