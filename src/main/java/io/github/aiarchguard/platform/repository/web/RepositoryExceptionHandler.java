package io.github.aiarchguard.platform.repository.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.repository.InvalidRepositoryException;
import io.github.aiarchguard.platform.repository.RepositoryConflictException;
import io.github.aiarchguard.platform.repository.RepositoryNotFoundException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将 Repository 专属异常映射为统一 ApiError，保留固定业务错误码与当前 traceId 供调用方关联请求。
 * 项目不可见或角色不足属于 Project 异常，由对应处理器映射，不在这里转换为仓库错误。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RepositoryExceptionHandler {
    private final TraceIdProvider traceIds;
    RepositoryExceptionHandler(TraceIdProvider traceIds) { this.traceIds = traceIds; }

    /** 输入校验失败返回 400，消息使用应用层校验异常提供的说明。 */
    @ExceptionHandler(InvalidRepositoryException.class)
    ResponseEntity<ApiError> invalid(InvalidRepositoryException exception) {
        return error(HttpStatus.BAD_REQUEST, "repository.invalid", exception.getMessage());
    }
    /** 项目与仓库组合未命中时返回 404，不说明仓库是否属于其他项目。 */
    @ExceptionHandler(RepositoryNotFoundException.class)
    ResponseEntity<ApiError> notFound() { return error(HttpStatus.NOT_FOUND, "repository.not_found", "Repository was not found."); }
    /** 当前唯一键冲突统一返回 409 和 key_conflict，不向客户端暴露数据库约束细节。 */
    @ExceptionHandler(RepositoryConflictException.class)
    ResponseEntity<ApiError> conflict() { return error(HttpStatus.CONFLICT, "repository.key_conflict", "Repository key is already in use."); }
    /** 统一附带 traceId，当前仓库错误不提供额外详情字段。 */
    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, traceIds.currentTraceId(), Map.of()));
    }
}
