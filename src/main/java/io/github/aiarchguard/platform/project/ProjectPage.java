package io.github.aiarchguard.platform.project;

import java.util.List;

/**
 * 当前主体可见项目的分页快照：page 从 0 开始，size 为请求页大小，total 为可见项目总数。
 * 分页参数由应用入口校验；超过末页时可以返回空 items，total 仍描述整个授权结果集。
 */
public record ProjectPage(List<ProjectView> items, int page, int size, long total) {
    public ProjectPage {
        // 固定本页列表，避免调用方持有的可变集合改变已返回的查询结果。
        items = List.copyOf(items);
    }
}
