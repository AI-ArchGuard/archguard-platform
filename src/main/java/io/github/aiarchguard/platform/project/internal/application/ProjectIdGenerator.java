package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.ProjectId;

/**
 * 创建项目时分配稳定标识的应用端口，将随机数来源与领域对象分离。
 * 生产适配器生成 UUID，测试可以提供确定的标识；项目业务 key 不承担主键职责。
 */
public interface ProjectIdGenerator {
    /** 为待创建的项目提供标识；数据库仍负责校验主键唯一性。 */
    ProjectId nextId();
}
