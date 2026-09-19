package io.github.aiarchguard.platform.scanjob.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.scanjob.InvalidScanJobException;
import io.github.aiarchguard.platform.scanjob.ScanJobConflictException;
import io.github.aiarchguard.platform.scanjob.ScanJobNotFoundException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ScanJobExceptionHandler {
    private final TraceIdProvider traceIds;
    ScanJobExceptionHandler(TraceIdProvider traceIds) { this.traceIds = traceIds; }
    @ExceptionHandler(InvalidScanJobException.class)
    ResponseEntity<ApiError> invalid(InvalidScanJobException e) { return error(HttpStatus.BAD_REQUEST,"scan_job.invalid",e.getMessage()); }
    @ExceptionHandler(ScanJobNotFoundException.class)
    ResponseEntity<ApiError> missing() { return error(HttpStatus.NOT_FOUND,"scan_job.not_found","Scan job was not found."); }
    @ExceptionHandler(ScanJobConflictException.class)
    ResponseEntity<ApiError> conflict(ScanJobConflictException e) { return error(HttpStatus.CONFLICT,"scan_job.conflict",e.getMessage()); }
    private ResponseEntity<ApiError> error(HttpStatus status,String code,String message) {
        return ResponseEntity.status(status).body(new ApiError(code,message,traceIds.currentTraceId(), Map.of()));
    }
}
