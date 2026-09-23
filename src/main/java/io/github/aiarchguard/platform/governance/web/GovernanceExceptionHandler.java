package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.governance.BaselineNotFoundException;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class GovernanceExceptionHandler {
    private final TraceIdProvider traceIds;
    GovernanceExceptionHandler(TraceIdProvider traceIds) { this.traceIds = traceIds; }

    @ExceptionHandler(BaselineNotFoundException.class)
    ResponseEntity<ApiError> missing() {
        return error(HttpStatus.NOT_FOUND, "baseline.not_found", "Baseline was not found.");
    }
    @ExceptionHandler(InvalidGovernanceInputException.class)
    ResponseEntity<ApiError> invalid(InvalidGovernanceInputException exception) {
        return error(HttpStatus.BAD_REQUEST, "governance.invalid", exception.getMessage());
    }
    @ExceptionHandler(InvalidGovernanceReportException.class)
    ResponseEntity<ApiError> invalidReport(InvalidGovernanceReportException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "governance.report_invalid", exception.getMessage());
    }
    @ExceptionHandler(GovernanceConflictException.class)
    ResponseEntity<ApiError> conflict(GovernanceConflictException exception) {
        return error(HttpStatus.CONFLICT, "governance.conflict", exception.getMessage());
    }
    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, traceIds.currentTraceId(), Map.of()));
    }
}
