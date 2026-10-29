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

/**
 * 将 ScanJob 专属异常映射为统一 ApiError，携带当前 traceId 便于关联请求。
 * 输入无效、资源缺失和状态/幂等冲突分别对应 400、404、409；Project 授权异常由其既有边界处理。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ScanJobExceptionHandler {
    private final TraceIdProvider traceIds;
    ScanJobExceptionHandler(TraceIdProvider traceIds) { this.traceIds = traceIds; }
    /** 保留应用层给出的参数错误说明，不向外暴露内部堆栈。 */
    @ExceptionHandler(InvalidScanJobException.class)
    ResponseEntity<ApiError> invalid(InvalidScanJobException e) { return error(HttpStatus.BAD_REQUEST,"scan_job.invalid",e.getMessage()); }
    /** 缺失响应使用固定文案，不区分未知 ID 与指定项目下无此任务。 */
    @ExceptionHandler(ScanJobNotFoundException.class)
    ResponseEntity<ApiError> missing() { return error(HttpStatus.NOT_FOUND,"scan_job.not_found","Scan job was not found."); }
    /** 幂等键输入冲突与结果尚不可用都属于业务冲突，使用相同模块错误结构。 */
    @ExceptionHandler(ScanJobConflictException.class)
    ResponseEntity<ApiError> conflict(ScanJobConflictException e) { return error(HttpStatus.CONFLICT,"scan_job.conflict",e.getMessage()); }
    /** 模块错误统一携带业务码、说明、traceId 和空扩展字段。 */
    private ResponseEntity<ApiError> error(HttpStatus status,String code,String message) {
        return ResponseEntity.status(status).body(new ApiError(code,message,traceIds.currentTraceId(), Map.of()));
    }
}
