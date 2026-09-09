package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectMemberPage;
import java.util.List;

record ProjectMemberListResponse(List<ProjectMemberResponse> items, int page, int size, long total) {
    static ProjectMemberListResponse from(ProjectMemberPage memberPage) {
        return new ProjectMemberListResponse(
            memberPage.items().stream().map(ProjectMemberResponse::from).toList(),
            memberPage.page(), memberPage.size(), memberPage.total());
    }
}
