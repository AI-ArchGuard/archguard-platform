package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.project.InvalidProjectException;
import io.github.aiarchguard.platform.project.LastProjectMaintainerException;
import io.github.aiarchguard.platform.project.ProjectKeyConflictException;
import io.github.aiarchguard.platform.project.ProjectMemberNotFoundException;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import io.github.aiarchguard.platform.project.ProjectVersionConflictException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ProjectExceptionHandler {
    private final TraceIdProvider traceIds;

    ProjectExceptionHandler(TraceIdProvider traceIds) {
        this.traceIds = traceIds;
    }

    @ExceptionHandler(InvalidProjectException.class)
    ResponseEntity<ApiError> invalidProject(InvalidProjectException exception) {
        return error(HttpStatus.BAD_REQUEST, "project.invalid", exception.getMessage());
    }

    @ExceptionHandler(ProjectKeyConflictException.class)
    ResponseEntity<ApiError> conflict(ProjectKeyConflictException exception) {
        return error(HttpStatus.CONFLICT, "project.key_conflict", "The project key is already in use.");
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    ResponseEntity<ApiError> notFound(ProjectNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "project.not_found", "Project was not found.");
    }

    @ExceptionHandler(ProjectPermissionDeniedException.class)
    ResponseEntity<ApiError> denied(ProjectPermissionDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, "authorization.denied",
            "The current actor is not allowed to perform this action.");
    }

    @ExceptionHandler(ProjectVersionConflictException.class)
    ResponseEntity<ApiError> versionConflict(ProjectVersionConflictException exception) {
        return error(HttpStatus.CONFLICT, "project.version_conflict",
            "The project was modified by another request.");
    }

    @ExceptionHandler(ProjectMemberNotFoundException.class)
    ResponseEntity<ApiError> memberNotFound(ProjectMemberNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "project.member_not_found", "Project member was not found.");
    }

    @ExceptionHandler(LastProjectMaintainerException.class)
    ResponseEntity<ApiError> lastMaintainer(LastProjectMaintainerException exception) {
        return error(HttpStatus.CONFLICT, "project.last_maintainer",
            "A project must retain at least one maintainer.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
            .body(new ApiError(code, message, traceIds.currentTraceId(), Map.of()));
    }
}
