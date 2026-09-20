package io.github.aiarchguard.platform.finding.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.finding.FindingNotFoundException;
import io.github.aiarchguard.platform.finding.FindingVersionConflictException;
import io.github.aiarchguard.platform.finding.InvalidDispositionException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class FindingExceptionHandler{
    private final TraceIdProvider traceIds;
    FindingExceptionHandler(TraceIdProvider traceIds){this.traceIds=traceIds;}
    @ExceptionHandler(FindingNotFoundException.class) ResponseEntity<ApiError> missing(){return error(HttpStatus.NOT_FOUND,"finding.not_found","Finding or evidence was not found.");}
    @ExceptionHandler(InvalidDispositionException.class) ResponseEntity<ApiError> invalid(InvalidDispositionException e){return error(HttpStatus.BAD_REQUEST,"finding.disposition_invalid",e.getMessage());}
    @ExceptionHandler(FindingVersionConflictException.class) ResponseEntity<ApiError> conflict(){return error(HttpStatus.CONFLICT,"finding.version_conflict","Finding was modified by another request.");}
    private ResponseEntity<ApiError> error(HttpStatus status,String code,String message){return ResponseEntity.status(status).body(new ApiError(code,message,traceIds.currentTraceId(), Map.of()));}
}
