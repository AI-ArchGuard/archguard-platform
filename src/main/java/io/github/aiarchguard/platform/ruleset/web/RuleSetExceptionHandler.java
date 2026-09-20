package io.github.aiarchguard.platform.ruleset.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.ruleset.InvalidRuleSetException;
import io.github.aiarchguard.platform.ruleset.RuleSetConflictException;
import io.github.aiarchguard.platform.ruleset.RuleSetNotFoundException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RuleSetExceptionHandler {
    private final TraceIdProvider traceIds;
    RuleSetExceptionHandler(TraceIdProvider traceIds) { this.traceIds = traceIds; }
    @ExceptionHandler(InvalidRuleSetException.class)
    ResponseEntity<ApiError> invalid(InvalidRuleSetException exception) { return error(HttpStatus.BAD_REQUEST, "ruleset.invalid", exception.getMessage()); }
    @ExceptionHandler(RuleSetNotFoundException.class)
    ResponseEntity<ApiError> notFound() { return error(HttpStatus.NOT_FOUND, "ruleset.not_found", "Rule set was not found."); }
    @ExceptionHandler(RuleSetConflictException.class)
    ResponseEntity<ApiError> conflict() { return error(HttpStatus.CONFLICT, "ruleset.key_conflict", "Rule set key is already in use."); }
    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, traceIds.currentTraceId(), Map.of()));
    }
}
