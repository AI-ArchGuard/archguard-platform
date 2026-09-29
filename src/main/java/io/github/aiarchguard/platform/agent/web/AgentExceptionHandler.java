package io.github.aiarchguard.platform.agent.web;

import io.github.aiarchguard.platform.agent.AgentConflictException;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agentdocument.DocumentNotFoundException;
import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.finding.FindingNotFoundException;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.scanjob.ScanJobNotFoundException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AgentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class AgentExceptionHandler {
    private final TraceIdProvider traceIds;
    AgentExceptionHandler(TraceIdProvider traceIds) { this.traceIds = traceIds; }

    @ExceptionHandler(AgentInvalidException.class)
    ResponseEntity<ApiError> invalid() { return error(HttpStatus.BAD_REQUEST, "agent.invalid", "Agent request is invalid."); }

    @ExceptionHandler(AgentConflictException.class)
    ResponseEntity<ApiError> conflict() {
        return error(HttpStatus.CONFLICT, "agent.idempotency_conflict", "Idempotency-Key is bound to different input.");
    }

    @ExceptionHandler({AgentNotFoundException.class, ProjectNotFoundException.class,
        FindingNotFoundException.class, DocumentNotFoundException.class, ScanJobNotFoundException.class})
    ResponseEntity<ApiError> missing() {
        return error(HttpStatus.NOT_FOUND, "agent.not_found", "Agent input or request was not found.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, traceIds.currentTraceId(), Map.of()));
    }
}
