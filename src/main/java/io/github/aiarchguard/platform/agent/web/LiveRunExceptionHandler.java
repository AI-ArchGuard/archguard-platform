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

/** Scope to the explicit live route; no exception, SQL, input or credential text is returned. */
@RestControllerAdvice(assignableTypes = AgentLiveController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class LiveRunExceptionHandler {
    private final TraceIdProvider traces;
    LiveRunExceptionHandler(TraceIdProvider traces) { this.traces = traces; }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> failure(Exception failure) {
        var error = failure instanceof AgentQuotaExceededException
            ? new BatchExceptionHandler.Failure(HttpStatus.TOO_MANY_REQUESTS, "agent.quota_exceeded")
            : BatchExceptionHandler.classify(failure);
        return ResponseEntity.status(error.status()).header("Cache-Control", "no-store").header("Pragma", "no-cache")
            .body(new ApiError(error.code(), "Agent live request could not be completed.", traces.currentTraceId(), Map.of()));
    }
}
