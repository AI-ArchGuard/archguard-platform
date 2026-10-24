package io.github.aiarchguard.platform.project;

/**
 * 项目输入不符合业务规则时的公开异常，如 key、名称、分页、期望版本或成员参数无效。
 * Web 层将其映射为 400，让调用方修正输入后再提交。
 */
public final class InvalidProjectException extends RuntimeException {
    public InvalidProjectException(String message) {
        super(message);
    }
}
