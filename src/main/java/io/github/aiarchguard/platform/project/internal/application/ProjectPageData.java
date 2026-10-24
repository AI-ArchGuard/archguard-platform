package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.Project;
import java.util.List;

/**
 * 只读用例返回的内部项目分页数据：本页领域对象与当前操作者可见项目的总数。
 * 页码和页大小由外层应用服务保留，并在那里转换为公开的 {@code ProjectPage}。
 */
public record ProjectPageData(List<Project> items, long total) {
    /** 固定本页集合，避免调用方修改列表后改变已取得的查询结果。 */
    public ProjectPageData {
        items = List.copyOf(items);
    }
}
