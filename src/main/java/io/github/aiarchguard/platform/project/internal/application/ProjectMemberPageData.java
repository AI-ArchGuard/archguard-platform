package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.ProjectMember;
import java.util.List;

public record ProjectMemberPageData(List<ProjectMember> items, long total) {
    public ProjectMemberPageData {
        items = List.copyOf(items);
    }
}
