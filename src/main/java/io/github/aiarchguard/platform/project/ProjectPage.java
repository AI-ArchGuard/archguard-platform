package io.github.aiarchguard.platform.project;

import java.util.List;

public record ProjectPage(List<ProjectView> items, int page, int size, long total) {
    public ProjectPage {
        items = List.copyOf(items);
    }
}
