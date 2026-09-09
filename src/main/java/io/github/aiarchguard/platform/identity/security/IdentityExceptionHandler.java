package io.github.aiarchguard.platform.identity.security;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.InvalidCurrentActorException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class IdentityExceptionHandler {
    private final TraceIdProvider traceIds;

    IdentityExceptionHandler(TraceIdProvider traceIds) {
        this.traceIds = traceIds;
    }

    @ExceptionHandler(InvalidCurrentActorException.class)
    ResponseEntity<ApiError> invalidActor(InvalidCurrentActorException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new ApiError("authentication.invalid", "Authentication is invalid.",
                traceIds.currentTraceId(), Map.of()));
    }
}
