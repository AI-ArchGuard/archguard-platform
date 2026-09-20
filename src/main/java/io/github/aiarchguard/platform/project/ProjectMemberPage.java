package io.github.aiarchguard.platform.project;

import java.util.List;

public record ProjectMemberPage(List<ProjectMemberView> items, int page, int size, long total) {
    public ProjectMemberPage {
        items = List.copyOf(items);
    }
}
