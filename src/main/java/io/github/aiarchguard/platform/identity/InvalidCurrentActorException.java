package io.github.aiarchguard.platform.identity;

/** 已认证上下文中的操作者标识不能解析为业务 UUID 时拒绝继续执行。 */
public final class InvalidCurrentActorException extends RuntimeException {
    public InvalidCurrentActorException() {
        super("The authenticated actor identifier is invalid");
    }
}
