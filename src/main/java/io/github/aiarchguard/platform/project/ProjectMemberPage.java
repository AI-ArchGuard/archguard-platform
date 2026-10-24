package io.github.aiarchguard.platform.project;

import java.util.List;

/**
 * 已获授权项目的成员分页快照：page 从 0 开始，size 为请求页大小，total 为该项目的成员总数。
 * 页码和大小由应用入口校验；此公开类型不暴露领域成员对象或持久化模型。
 */
public record ProjectMemberPage(List<ProjectMemberView> items, int page, int size, long total) {
    public ProjectMemberPage {
        // 防御性复制让调用方修改原列表时不会改变已经返回的本页内容。
        items = List.copyOf(items);
    }
}
