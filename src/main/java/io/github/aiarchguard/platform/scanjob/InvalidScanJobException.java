package io.github.aiarchguard.platform.scanjob;

/** 扫描请求的幂等键或分页参数无效；Web 边界将其转换为统一的 400 错误。 */
public final class InvalidScanJobException extends RuntimeException {
    public InvalidScanJobException(String message) { super(message); }
}
