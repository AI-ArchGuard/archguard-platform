package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.ProjectMember;
import java.util.List;

/**
 * 已通过项目可见性检查后的成员分页结果，携带本页成员及该项目的成员总数。
 * 这是内部领域数据，外层应用服务负责映射成公开成员视图并补上分页参数。
 */
public record ProjectMemberPageData(List<ProjectMember> items, long total) {
    /** 保存不可变的列表副本，让调用方无法增删这次查询返回的成员。 */
    public ProjectMemberPageData {
        items = List.copyOf(items);
    }
}
