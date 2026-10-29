package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.project.InvalidProjectException;
import io.github.aiarchguard.platform.project.LastProjectMaintainerException;
import io.github.aiarchguard.platform.project.ProjectKeyConflictException;
import io.github.aiarchguard.platform.project.ProjectMemberNotFoundException;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectNotEmptyException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import io.github.aiarchguard.platform.project.ProjectVersionConflictException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把 Project 业务异常翻译成稳定的 HTTP 状态与错误码，避免客户端依赖 Java 异常类型。
 * 只负责协议转换；它不恢复事务、不重试请求，也不会把失败操作变成成功。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ProjectExceptionHandler {
    private final TraceIdProvider traceIds;

    ProjectExceptionHandler(TraceIdProvider traceIds) {
        this.traceIds = traceIds;
    }

    @ExceptionHandler(InvalidProjectException.class)
    // 领域校验不通过属于请求无效；其他框架层的参数错误由通用错误处理链负责。
    ResponseEntity<ApiError> invalidProject(InvalidProjectException exception) {
        return error(HttpStatus.BAD_REQUEST, "project.invalid", exception.getMessage());
    }

    @ExceptionHandler(ProjectKeyConflictException.class)
    // 冲突只说明 key 已被使用，不泄露占用该 key 的项目或成员信息。
    ResponseEntity<ApiError> conflict(ProjectKeyConflictException exception) {
        return error(HttpStatus.CONFLICT, "project.key_conflict", "The project key is already in use.");
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    // 应用层已把“项目不存在”和“非成员不可见”统一为此异常，HTTP 层不再区分。
    ResponseEntity<ApiError> notFound(ProjectNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "project.not_found", "Project was not found.");
    }

    @ExceptionHandler(ProjectPermissionDeniedException.class)
    // 与资源不可见的 404 区分：这里表示缺少创建权限或已有成员缺少维护权限。
    ResponseEntity<ApiError> denied(ProjectPermissionDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, "authorization.denied",
            "The current actor is not allowed to perform this action.");
    }

    @ExceptionHandler(ProjectVersionConflictException.class)
    // 返回冲突，要求客户端重新读取版本；不能在这里静默覆盖或自动重试写操作。
    ResponseEntity<ApiError> versionConflict(ProjectVersionConflictException exception) {
        return error(HttpStatus.CONFLICT, "project.version_conflict",
            "The project was modified by another request.");
    }

    @ExceptionHandler(ProjectNotEmptyException.class)
    // 存在其他模块的内容时拒绝删除，不靠级联删除清空扫描、文档或 Agent 历史。
    ResponseEntity<ApiError> notEmpty(ProjectNotEmptyException exception) {
        return error(HttpStatus.CONFLICT, "project.not_empty",
            "Project must not contain repositories, rule sets, scan jobs, documents, or Agent history when deleted.");
    }

    @ExceptionHandler(ProjectMemberNotFoundException.class)
    // 这是目标成员关系不存在，与整个项目不可见使用不同的稳定错误码。
    ResponseEntity<ApiError> memberNotFound(ProjectMemberNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "project.member_not_found", "Project member was not found.");
    }

    @ExceptionHandler(LastProjectMaintainerException.class)
    // 项目不能失去最后一名维护者，属于业务状态冲突而不是服务端故障。
    ResponseEntity<ApiError> lastMaintainer(LastProjectMaintainerException exception) {
        return error(HttpStatus.CONFLICT, "project.last_maintainer",
            "A project must retain at least one maintainer.");
    }

    /** 所有错误复用当前 traceId，details 保持为空，不附带异常堆栈或内部数据库信息。 */
    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
            .body(new ApiError(code, message, traceIds.currentTraceId(), Map.of()));
    }
}
