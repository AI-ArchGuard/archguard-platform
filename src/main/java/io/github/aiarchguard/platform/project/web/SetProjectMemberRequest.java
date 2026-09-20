package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectMemberRole;
import jakarta.validation.constraints.NotNull;

record SetProjectMemberRequest(@NotNull ProjectMemberRole role) {}
