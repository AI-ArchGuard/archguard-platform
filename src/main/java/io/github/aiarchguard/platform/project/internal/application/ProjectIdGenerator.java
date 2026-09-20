package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.ProjectId;

public interface ProjectIdGenerator {
    ProjectId nextId();
}
