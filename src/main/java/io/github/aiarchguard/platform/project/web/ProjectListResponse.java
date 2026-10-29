package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectPage;
import java.util.List;

/**
 * 当前调用者可见项目的分页响应；page 为零基页号，total 是可见项目总数而非系统项目总数。
 * 这里只映射已授权的应用层结果，不在 HTTP 层重新查询或拼接数据。
 */
record ProjectListResponse(List<ProjectResponse> items, int page, int size, long total) {
    static ProjectListResponse from(ProjectPage projectPage) {
        return new ProjectListResponse(
            projectPage.items().stream().map(ProjectResponse::from).toList(),
            projectPage.page(), projectPage.size(), projectPage.total());
    }
}
