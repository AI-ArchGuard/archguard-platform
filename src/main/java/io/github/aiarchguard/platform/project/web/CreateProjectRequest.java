package io.github.aiarchguard.platform.project.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建项目的 HTTP 输入：key 是稳定标识，name 是展示名称，不接收创建者或角色字段。
 * Bean Validation 先过滤明显无效的输入，领域值对象还会执行自身的规范化与校验。
 */
record CreateProjectRequest(
    @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{2,62}$") String key,
    @NotBlank @Size(max = 120) String name) {}
