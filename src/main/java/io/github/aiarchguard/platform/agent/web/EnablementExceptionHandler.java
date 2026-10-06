package io.github.aiarchguard.platform.agent.web;

import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.AgentSettingsPreconditionException;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;

/** Runs after the application transaction rolls back; never retains submitted input or exceptions. */
@RestControllerAdvice(assignableTypes = AgentEnablementController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class EnablementExceptionHandler {
    private final AuditRecorder audit;
    private final CurrentActorProvider actors;
    private final TraceIdProvider traces;
    EnablementExceptionHandler(AuditRecorder audit, CurrentActorProvider actors, TraceIdProvider traces) {
        this.audit = audit; this.actors = actors; this.traces = traces;
    }

    @ExceptionHandler({AgentInvalidException.class, AgentEnablementInvalidStateException.class,
        AgentSettingsPreconditionException.class, AgentUnavailableException.class, CredentialFailure.class,
        ProjectPermissionDeniedException.class, ProjectNotFoundException.class, AgentNotFoundException.class,
        DataAccessException.class})
    ResponseEntity<ApiError> failure(Exception failure, HttpServletRequest request) {
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        String code = "agent.unavailable";
        if (failure instanceof AgentInvalidException) { status = HttpStatus.BAD_REQUEST; code = "agent.invalid"; }
        else if (failure instanceof AgentSettingsPreconditionException condition) {
            status = condition.missing() ? HttpStatus.PRECONDITION_REQUIRED : HttpStatus.PRECONDITION_FAILED;
            code = condition.missing() ? "agent.precondition_required" : "agent.precondition_failed";
        } else if (failure instanceof AgentEnablementInvalidStateException) {
            status = HttpStatus.CONFLICT; code = "agent.enablement_invalid";
        } else if (failure instanceof ProjectPermissionDeniedException
                || failure instanceof CredentialFailure credential && credential.kind() == CredentialFailure.Kind.DENIED) {
            status = HttpStatus.FORBIDDEN; code = "agent.forbidden";
        } else if (failure instanceof ProjectNotFoundException || failure instanceof AgentNotFoundException) {
            status = HttpStatus.NOT_FOUND; code = "agent.not_found";
        }
        if (!"GET".equals(request.getMethod())) {
            Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            UUID project = variables instanceof Map<?, ?> map && map.get("projectId") instanceof String id ? UUID.fromString(id) : null;
            AuditResult result = status == HttpStatus.FORBIDDEN ? AuditResult.DENIED
                : status == HttpStatus.CONFLICT || status == HttpStatus.PRECONDITION_FAILED ? AuditResult.CONFLICT : AuditResult.FAILURE;
            try {
                audit.record(new AuditEvent(actors.currentActor().id(), project, "agent.enablement.write_rejected", result,
                    traces.currentTraceId(), Map.of("reason", code, "status", status.value())));
            } catch (RuntimeException unavailableAudit) {
                // Failure is explicit, not an unaudited success or a leak of storage/input details.
                status = HttpStatus.SERVICE_UNAVAILABLE; code = "agent.audit_unavailable";
            }
        }
        return ResponseEntity.status(status).body(new ApiError(code, "Agent enablement operation could not be completed.", traces.currentTraceId(), Map.of()));
    }
}
