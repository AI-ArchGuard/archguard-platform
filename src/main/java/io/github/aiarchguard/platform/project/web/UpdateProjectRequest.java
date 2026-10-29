package io.github.aiarchguard.platform.project.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 修改项目名称的请求；version 表示客户端期望的当前版本，用于发现并发修改。
 * 使用可空的 Long 配合 NotNull 区分“没有传版本”和合法的初始版本 0；不开放修改 key。
 */
record UpdateProjectRequest(
    @NotBlank @Size(max = 120) String name,
    @NotNull @PositiveOrZero Long version
) {}
