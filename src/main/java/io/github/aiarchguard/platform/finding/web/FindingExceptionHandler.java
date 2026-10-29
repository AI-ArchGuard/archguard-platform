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

/**
 * 将 Finding 公开用例的资源缺失、输入错误和版本冲突映射为统一 HTTP 错误，并附当前 traceId。
 * Project 授权错误由对应公共处理器处理；内部 InvalidResultException 不在本类映射范围内。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class FindingExceptionHandler{
    private final TraceIdProvider traceIds;
    FindingExceptionHandler(TraceIdProvider traceIds){this.traceIds=traceIds;}
    /** Finding 与证据共用 404 表达，避免向调用方暴露范围外资源的具体类别。 */
    @ExceptionHandler(FindingNotFoundException.class) ResponseEntity<ApiError> missing(){return error(HttpStatus.NOT_FOUND,"finding.not_found","Finding or evidence was not found.");}
    @ExceptionHandler(InvalidDispositionException.class) ResponseEntity<ApiError> invalid(InvalidDispositionException e){return error(HttpStatus.BAD_REQUEST,"finding.disposition_invalid",e.getMessage());}
    /** 条件更新未命中时返回 409，调用方需重读当前版本再决定是否提交新的处置。 */
    @ExceptionHandler(FindingVersionConflictException.class) ResponseEntity<ApiError> conflict(){return error(HttpStatus.CONFLICT,"finding.version_conflict","Finding was modified by another request.");}
    private ResponseEntity<ApiError> error(HttpStatus status,String code,String message){return ResponseEntity.status(status).body(new ApiError(code,message,traceIds.currentTraceId(), Map.of()));}
}
