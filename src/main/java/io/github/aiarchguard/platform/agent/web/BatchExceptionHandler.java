package io.github.aiarchguard.platform.agent.web;

import io.github.aiarchguard.platform.agent.AgentConflictException;
import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.InvalidCurrentActorException;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Scoped sanitization only; application and boundary failures are audited at their owning layer. */
@RestControllerAdvice(assignableTypes = AgentBatchController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class BatchExceptionHandler {
    private final TraceIdProvider traces;
    BatchExceptionHandler(TraceIdProvider traces) { this.traces = traces; }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> failure(Exception failure) {
        var error = classify(failure);
        return ResponseEntity.status(error.status()).body(new ApiError(error.code(),
            "Agent batch operation could not be completed.", traces.currentTraceId(), Map.of()));
    }

    static Failure classify(Exception failure) {
        if (failure instanceof AgentInvalidException || failure instanceof MethodArgumentTypeMismatchException
                || failure instanceof ServletRequestBindingException || failure instanceof HttpMessageNotReadableException
                || failure instanceof CredentialFailure credential && credential.kind() == CredentialFailure.Kind.INVALID) {
            return new Failure(HttpStatus.BAD_REQUEST, "agent.invalid");
        }
        if (failure instanceof InvalidCurrentActorException) {
            return new Failure(HttpStatus.UNAUTHORIZED, "agent.unauthorized");
        }
        if (failure instanceof ProjectPermissionDeniedException
                || failure instanceof CredentialFailure credential && credential.kind() == CredentialFailure.Kind.DENIED) {
            return new Failure(HttpStatus.FORBIDDEN, "agent.forbidden");
        }
        if (failure instanceof AgentEnablementInvalidStateException) {
            return new Failure(HttpStatus.CONFLICT, "agent.enablement_invalid");
        }
        if (failure instanceof AgentConflictException) {
            return new Failure(HttpStatus.CONFLICT, "agent.conflict");
        }
        if (failure instanceof ProjectNotFoundException || failure instanceof AgentNotFoundException) {
            return new Failure(HttpStatus.NOT_FOUND, "agent.not_found");
        }
        return new Failure(HttpStatus.SERVICE_UNAVAILABLE, "agent.unavailable");
    }

    record Failure(HttpStatus status, String code) { }
}
