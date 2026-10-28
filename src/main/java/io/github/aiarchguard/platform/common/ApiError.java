package io.github.aiarchguard.platform.common;

import java.util.Map;

/** HTTP 层统一错误结构：固定错误码、可读消息、追踪标识和受控详情，不承载原始异常或凭据。 */
public record ApiError(String code, String message, String traceId, Map<String, Object> details) {
    public ApiError {
        details = Map.copyOf(details);
    }
}
