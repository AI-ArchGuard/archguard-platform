package io.github.aiarchguard.platform.agentcredential.web;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CredentialController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class CredentialExceptionHandler {
    private final TraceIdProvider traces;
    CredentialExceptionHandler(TraceIdProvider traces) { this.traces = traces; }
    @ExceptionHandler(CredentialFailure.class)
    ResponseEntity<ApiError> failed(CredentialFailure failure) {
        int status = switch (failure.kind()) { case INVALID -> 400; case DENIED -> 403; case UNAVAILABLE -> 503; };
        return ResponseEntity.status(status).body(new ApiError(failure.getMessage(),
            "Credential operation could not be completed.", traces.currentTraceId(), Map.of()));
    }
}
