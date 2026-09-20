package io.github.aiarchguard.platform.common;

import java.util.Map;

public record ApiError(String code, String message, String traceId, Map<String, Object> details) {
    public ApiError {
        details = Map.copyOf(details);
    }
}
