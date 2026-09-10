package io.github.aiarchguard.platform.project.infrastructure;

import io.github.aiarchguard.platform.project.internal.application.ProjectIdGenerator;
import io.github.aiarchguard.platform.project.internal.domain.ProjectId;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class RandomProjectIdGenerator implements ProjectIdGenerator {
    @Override
    public ProjectId nextId() {
        return new ProjectId(UUID.randomUUID());
    }
}
