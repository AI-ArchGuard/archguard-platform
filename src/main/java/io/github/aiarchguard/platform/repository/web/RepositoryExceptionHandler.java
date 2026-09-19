package io.github.aiarchguard.platform.repository.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.repository.InvalidRepositoryException;
import io.github.aiarchguard.platform.repository.RepositoryConflictException;
import io.github.aiarchguard.platform.repository.RepositoryNotFoundException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RepositoryExceptionHandler {
    private final TraceIdProvider traceIds;
    RepositoryExceptionHandler(TraceIdProvider traceIds) { this.traceIds = traceIds; }

    @ExceptionHandler(InvalidRepositoryException.class)
    ResponseEntity<ApiError> invalid(InvalidRepositoryException exception) {
        return error(HttpStatus.BAD_REQUEST, "repository.invalid", exception.getMessage());
    }
    @ExceptionHandler(RepositoryNotFoundException.class)
    ResponseEntity<ApiError> notFound() { return error(HttpStatus.NOT_FOUND, "repository.not_found", "Repository was not found."); }
    @ExceptionHandler(RepositoryConflictException.class)
    ResponseEntity<ApiError> conflict() { return error(HttpStatus.CONFLICT, "repository.key_conflict", "Repository key is already in use."); }
    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, traceIds.currentTraceId(), Map.of()));
    }
}
