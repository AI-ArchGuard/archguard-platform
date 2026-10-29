package io.github.aiarchguard.platform.project;

/**
 * 修改或删除时，期望版本与当前项目不符或条件写入未命中的业务冲突，Web 层映射为 409。
 * 调用方应重新读取项目状态，再决定是否基于新版本提交，避免静默覆盖并发修改。
 */
public final class ProjectVersionConflictException extends RuntimeException {
    public ProjectVersionConflictException() {
        super("The project was modified by another request");
    }
}
