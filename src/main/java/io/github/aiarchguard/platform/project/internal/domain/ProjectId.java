package io.github.aiarchguard.platform.project.internal.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 用独立类型表达项目标识，帮助领域模型区分项目 UUID 与成员主体 UUID。
 * 这里只要求 UUID 非空；生成策略由应用端口及其基础设施实现决定，不在值对象中限制 UUID 版本。
 */
public record ProjectId(UUID value) {
    public ProjectId {
        Objects.requireNonNull(value, "value");
    }
}
