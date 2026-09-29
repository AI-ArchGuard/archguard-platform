package io.github.aiarchguard.platform.agentdocument.web;

import io.github.aiarchguard.platform.agentdocument.DocumentConflictException;
import io.github.aiarchguard.platform.agentdocument.DocumentNotFoundException;
import io.github.aiarchguard.platform.agentdocument.DocumentTooLargeException;
import io.github.aiarchguard.platform.agentdocument.InvalidDocumentException;
import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DocumentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class DocumentExceptionHandler {
    private final TraceIdProvider traceIds;

    DocumentExceptionHandler(TraceIdProvider traceIds) { this.traceIds = traceIds; }

    @ExceptionHandler(InvalidDocumentException.class)
    ResponseEntity<ApiError> invalid() { return error(HttpStatus.BAD_REQUEST, "document.invalid", "Document input is invalid."); }

    @ExceptionHandler(DocumentTooLargeException.class)
    ResponseEntity<ApiError> tooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "document.too_large", "Document exceeds the size limit.");
    }

    @ExceptionHandler(DocumentConflictException.class)
    ResponseEntity<ApiError> conflict() {
        return error(HttpStatus.CONFLICT, "document.idempotency_conflict", "Idempotency-Key is bound to different document input.");
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return error(HttpStatus.NOT_FOUND, "document.not_found", "Document was not found.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiError> missingPart() {
        return error(HttpStatus.BAD_REQUEST, "document.invalid", "Document input is invalid.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, traceIds.currentTraceId(), Map.of()));
    }
}
