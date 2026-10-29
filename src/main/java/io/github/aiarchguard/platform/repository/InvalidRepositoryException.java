package io.github.aiarchguard.platform.repository;

/**
 * 仓库登记输入不符合业务规则时使用的异常，例如标识、名称或受控目录路径非法。
 * Web 层将其转为 HTTP 400，并使用这里提供的业务说明作为统一错误消息。
 */
public final class InvalidRepositoryException extends RuntimeException {
    public InvalidRepositoryException(String message) {
        super(message);
    }
}
