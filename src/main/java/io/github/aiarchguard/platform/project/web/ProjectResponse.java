package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectView;
import java.time.Instant;
import java.util.UUID;

/**
 * 项目 HTTP 响应：createdBy 是身份 UUID，version 供后续修改和删除使用，不是产品发布版本。
 * 单独转换应用视图，避免把领域对象或数据库行直接作为协议暴露。
 */
record ProjectResponse(UUID id, String key, String name, UUID createdBy, Instant createdAt, long version) {
    static ProjectResponse from(ProjectView view) {
        return new ProjectResponse(view.id(), view.key(), view.name(), view.createdBy(), view.createdAt(),
            view.version());
    }
}
