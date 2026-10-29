package io.github.aiarchguard.platform.project;

/**
 * 删除前保护器报告阻止条件时的业务冲突，Web 层映射为 409。
 * 通常表示项目仍有子资源或历史；依赖模块无法安全确认状态时也可能保守阻止删除。
 */
public final class ProjectNotEmptyException extends RuntimeException { }
