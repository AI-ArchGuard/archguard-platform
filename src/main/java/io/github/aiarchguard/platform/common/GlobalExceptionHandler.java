package io.github.aiarchguard.platform.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
final class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final TraceIdProvider traceIds;

    GlobalExceptionHandler(TraceIdProvider traceIds) {
        this.traceIds = traceIds;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        Map<String, Object> fields = exception.getBindingResult().getFieldErrors().stream()
            .sorted((left, right) -> left.getField().compareTo(right.getField()))
            .collect(Collectors.toMap(
                field -> field.getField(),
                field -> field.getDefaultMessage() == null ? "invalid" : field.getDefaultMessage(),
                (first, ignored) -> first,
                LinkedHashMap::new));
        return error(HttpStatus.BAD_REQUEST, "validation.failed", "Request validation failed.",
            Map.of("fields", fields));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> malformedRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "request.malformed", "The request is malformed.", Map.of());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> databaseUnavailable(DataAccessException exception) {
        LOGGER.error("event=request_failed reason=database exception={}", exception.getClass().getName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "dependency.unavailable",
            "A required service is temporarily unavailable.", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        LOGGER.error("event=request_failed reason=unexpected exception={}", exception.getClass().getName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal.error", "An unexpected error occurred.", Map.of());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message,
                                           Map<String, Object> details) {
        return ResponseEntity.status(status)
            .body(new ApiError(code, message, traceIds.currentTraceId(), details));
    }
}
