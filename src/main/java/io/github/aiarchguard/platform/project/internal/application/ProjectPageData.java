package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.Project;
import java.util.List;

public record ProjectPageData(List<Project> items, long total) {
    public ProjectPageData {
        items = List.copyOf(items);
    }
}
