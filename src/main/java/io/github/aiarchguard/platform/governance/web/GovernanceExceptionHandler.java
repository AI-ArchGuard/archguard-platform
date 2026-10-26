package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.common.ApiError;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.governance.BaselineNotFoundException;
import io.github.aiarchguard.platform.governance.ComparisonNotFoundException;
import io.github.aiarchguard.platform.governance.GateEvaluationNotFoundException;
import io.github.aiarchguard.platform.governance.GithubLinkNotFoundException;
import io.github.aiarchguard.platform.governance.GithubPullRequestNotFoundException;
import io.github.aiarchguard.platform.governance.GithubWebhookUnavailableException;
import io.github.aiarchguard.platform.governance.GovernanceReportTooLargeException;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import io.github.aiarchguard.platform.governance.InvalidGithubWebhookException;
import io.github.aiarchguard.platform.governance.PolicyExceptionNotFoundException;
import io.github.aiarchguard.platform.governance.ReportSubmissionNotFoundException;
import io.github.aiarchguard.platform.finding.InvalidResultException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将这里列明的治理异常（含复用的 Finding 报告校验异常）适配成统一 ApiError，并关联当前 traceId。
 * 资源缺失、输入非法、报告不可消费和既有事实冲突分别映射 404、400、422、409；
 * HTTP 错误与持久化 GateOutcome.ERROR 是不同边界，这里不生成门禁或负责审计事务。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class GovernanceExceptionHandler {
    private final TraceIdProvider traceIds;
    GovernanceExceptionHandler(TraceIdProvider traceIds) { this.traceIds = traceIds; }

    /** 基线读取没有兼容版本时返回 404，不能用空集合或默认 PASS 代替。 */
    @ExceptionHandler(BaselineNotFoundException.class)
    ResponseEntity<ApiError> missing() {
        return error(HttpStatus.NOT_FOUND, "baseline.not_found", "Baseline was not found.");
    }
    /** 例外资源不存在和例外已经到期/撤销是不同情况，后者仍可正常读取。 */
    @ExceptionHandler(PolicyExceptionNotFoundException.class)
    ResponseEntity<ApiError> missingException() {
        return error(HttpStatus.NOT_FOUND, "policy_exception.not_found", "Policy exception was not found.");
    }
    /** 门禁记录缺失，不等于一条已保存的 ERROR 门禁。 */
    @ExceptionHandler(GateEvaluationNotFoundException.class)
    ResponseEntity<ApiError> missingGate() {
        return error(HttpStatus.NOT_FOUND, "gate_evaluation.not_found", "Gate evaluation was not found.");
    }
    /** 这些资源共用稳定错误码；是否跨范围不可见由上游授权及范围查询决定。 */
    @ExceptionHandler({ComparisonNotFoundException.class, GithubLinkNotFoundException.class, GithubPullRequestNotFoundException.class,
        ReportSubmissionNotFoundException.class})
    ResponseEntity<ApiError> missingGithubResource() {
        return error(HttpStatus.NOT_FOUND, "governance.resource_not_found", "Governance resource was not found.");
    }
    /** Webhook 认证失败统一返回固定 401，不回显签名、正文或内部校验细节。 */
    @ExceptionHandler(InvalidGithubWebhookException.class)
    ResponseEntity<ApiError> invalidWebhook() {
        return error(HttpStatus.UNAUTHORIZED, "github.webhook_invalid", "GitHub webhook authentication failed.");
    }
    /** 缺少校验 Secret 属于入口未配置，返回 503，不允许绕过验证继续处理。 */
    @ExceptionHandler(GithubWebhookUnavailableException.class)
    ResponseEntity<ApiError> webhookUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "github.webhook_unavailable", "GitHub webhook is not configured.");
    }
    /** 原始报告超过提交契约的 50 MiB 上限。 */
    @ExceptionHandler(GovernanceReportTooLargeException.class)
    ResponseEntity<ApiError> reportTooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "governance.report_too_large", "Report exceeds 50 MiB.");
    }
    /** 返回应用校验生成的受控输入说明，调用方可修正元数据后重新提交。 */
    @ExceptionHandler(InvalidGovernanceInputException.class)
    ResponseEntity<ApiError> invalid(InvalidGovernanceInputException exception) {
        return error(HttpStatus.BAD_REQUEST, "governance.invalid", exception.getMessage());
    }
    /** 报告读取、格式、摘要或消费语义失败统一为 422，不把拒绝报告转成成功扫描。 */
    @ExceptionHandler({InvalidGovernanceReportException.class, InvalidResultException.class})
    ResponseEntity<ApiError> invalidReport(RuntimeException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "governance.report_invalid", exception.getMessage());
    }
    /** 既有事实与本次输入冲突时返回 409，原幂等结果或不可变绑定不被覆盖。 */
    @ExceptionHandler(GovernanceConflictException.class)
    ResponseEntity<ApiError> conflict(GovernanceConflictException exception) {
        return error(HttpStatus.CONFLICT, "governance.conflict", exception.getMessage());
    }
    /** 统一错误结构只携带代码、说明、traceId 和空详情；不附加报告或请求正文。 */
    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, traceIds.currentTraceId(), Map.of()));
    }
}
