package io.github.aiarchguard.platform.agent.web;

import io.github.aiarchguard.platform.agent.AgentQuotaExceededException;
import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 仅处理显式真实入口的错误：限额不足返回 429，其他错误复用稳定分类；响应不含输入、SQL、异常原文或凭据。 */
@RestControllerAdvice(assignableTypes = AgentLiveController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class LiveRunExceptionHandler {
    private final TraceIdProvider traces;
    LiveRunExceptionHandler(TraceIdProvider traces) { this.traces = traces; }

    /** 只在显式 live 路由公开固定错误码及 trace，限额映射 429，统一禁止缓存响应。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> failure(Exception failure) {
        var error = failure instanceof AgentQuotaExceededException
            ? new BatchExceptionHandler.Failure(HttpStatus.TOO_MANY_REQUESTS, "agent.quota_exceeded")
            : BatchExceptionHandler.classify(failure);
        return ResponseEntity.status(error.status()).header("Cache-Control", "no-store").header("Pragma", "no-cache")
            .body(new ApiError(error.code(), "Agent live request could not be completed.", traces.currentTraceId(), Map.of()));
    }
}
