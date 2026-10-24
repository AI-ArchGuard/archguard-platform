package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectMemberPage;
import java.util.List;

/** 单个已授权项目的成员分页；保持应用层的页号、页大小和总数，仅把成员视图转成响应 DTO。 */
record ProjectMemberListResponse(List<ProjectMemberResponse> items, int page, int size, long total) {
    static ProjectMemberListResponse from(ProjectMemberPage memberPage) {
        return new ProjectMemberListResponse(
            memberPage.items().stream().map(ProjectMemberResponse::from).toList(),
            memberPage.page(), memberPage.size(), memberPage.total());
    }
}
