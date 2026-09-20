package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectPage;
import java.util.List;

record ProjectListResponse(List<ProjectResponse> items, int page, int size, long total) {
    static ProjectListResponse from(ProjectPage projectPage) {
        return new ProjectListResponse(
            projectPage.items().stream().map(ProjectResponse::from).toList(),
            projectPage.page(), projectPage.size(), projectPage.total());
    }
}
