package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.governance.BaselineNotFoundException;
import io.github.aiarchguard.platform.governance.GateEvaluationNotFoundException;
import io.github.aiarchguard.platform.governance.GithubLinkNotFoundException;
import io.github.aiarchguard.platform.governance.GithubPullRequestNotFoundException;
import io.github.aiarchguard.platform.governance.GithubWebhookUnavailableException;
import io.github.aiarchguard.platform.governance.GovernanceReportTooLargeException;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import io.github.aiarchguard.platform.governance.InvalidGithubWebhookException;
import io.github.aiarchguard.platform.governance.PolicyExceptionNotFoundException;
import io.github.aiarchguard.platform.governance.ReportSubmissionNotFoundException;
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
    @ExceptionHandler(PolicyExceptionNotFoundException.class)
    ResponseEntity<ApiError> missingException() {
        return error(HttpStatus.NOT_FOUND, "policy_exception.not_found", "Policy exception was not found.");
    }
    @ExceptionHandler(GateEvaluationNotFoundException.class)
    ResponseEntity<ApiError> missingGate() {
        return error(HttpStatus.NOT_FOUND, "gate_evaluation.not_found", "Gate evaluation was not found.");
    }
    @ExceptionHandler({GithubLinkNotFoundException.class, GithubPullRequestNotFoundException.class,
        ReportSubmissionNotFoundException.class})
    ResponseEntity<ApiError> missingGithubResource() {
        return error(HttpStatus.NOT_FOUND, "governance.resource_not_found", "Governance resource was not found.");
    }
    @ExceptionHandler(InvalidGithubWebhookException.class)
    ResponseEntity<ApiError> invalidWebhook() {
        return error(HttpStatus.UNAUTHORIZED, "github.webhook_invalid", "GitHub webhook authentication failed.");
    }
    @ExceptionHandler(GithubWebhookUnavailableException.class)
    ResponseEntity<ApiError> webhookUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "github.webhook_unavailable", "GitHub webhook is not configured.");
    }
    @ExceptionHandler(GovernanceReportTooLargeException.class)
    ResponseEntity<ApiError> reportTooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "governance.report_too_large", "Report exceeds 50 MiB.");
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
