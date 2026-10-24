package io.github.aiarchguard.platform.ruleset.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.ruleset.InvalidRuleSetException;
import io.github.aiarchguard.platform.ruleset.RuleSetConflictException;
import io.github.aiarchguard.platform.ruleset.RuleSetNotFoundException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将规则集专属业务异常映射为统一 API 错误，优先于通用异常处理器匹配。
 * 错误体保留稳定错误码和当前 traceId，便于调用方判断原因并关联请求。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RuleSetExceptionHandler {
    private final TraceIdProvider traceIds;
    RuleSetExceptionHandler(TraceIdProvider traceIds) { this.traceIds = traceIds; }
    /** 输入或 Scanner 校验未成功统一返回 400，具体消息由应用层或校验适配器提供。 */
    @ExceptionHandler(InvalidRuleSetException.class)
    ResponseEntity<ApiError> invalid(InvalidRuleSetException exception) { return error(HttpStatus.BAD_REQUEST, "ruleset.invalid", exception.getMessage()); }
    /** 规则集或版本不在指定归属范围时返回 404，不在响应中区分不存在与归属不匹配。 */
    @ExceptionHandler(RuleSetNotFoundException.class)
    ResponseEntity<ApiError> notFound() { return error(HttpStatus.NOT_FOUND, "ruleset.not_found", "Rule set was not found."); }
    /** 元数据的 key 冲突返回 409，使调用方可以选择其他仓库内标识。 */
    @ExceptionHandler(RuleSetConflictException.class)
    ResponseEntity<ApiError> conflict() { return error(HttpStatus.CONFLICT, "ruleset.key_conflict", "Rule set key is already in use."); }
    /** 统一生成错误结构；此处附加信息为空，不将规则原文放入结构化详情。 */
    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, traceIds.currentTraceId(), Map.of()));
    }
}
