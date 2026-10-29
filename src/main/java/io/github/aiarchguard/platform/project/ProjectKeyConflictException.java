package io.github.aiarchguard.platform.project;

/**
 * 创建时项目 key 已被占用的业务冲突，Web 层映射为 409。
 * key 的全局唯一性由持久化约束裁决；公开响应不披露占用该 key 的项目或成员信息。
 */
public final class ProjectKeyConflictException extends RuntimeException {
    public ProjectKeyConflictException(String key) {
        super("Project key already exists: " + key);
    }
}
