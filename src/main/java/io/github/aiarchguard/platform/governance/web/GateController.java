package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.governance.GateEvaluationOperations;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.PolicyExceptionOperations;
import io.github.aiarchguard.platform.governance.PolicyExceptionScopeType;
import io.github.aiarchguard.platform.governance.PolicyExceptionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门禁与有期限例外的 HTTP 适配层，通过两个公开应用接口协作，不直接访问治理存储。
 * 当前主体的写权限和资源归属由应用层核验；例外是独立治理事实，不修改原始报告或 Finding 处置。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}")
final class GateController {
    private final GateEvaluationOperations gates;
    private final PolicyExceptionOperations exceptions;
    GateController(GateEvaluationOperations gates, PolicyExceptionOperations exceptions) {
        this.gates = gates; this.exceptions = exceptions;
    }

    /**
     * 传递 Project 范围内的幂等键并返回确切门禁；同键同输入可复用原结果，201 不意味着重新求值。
     * HTTP 接收成功与 PASS/FAIL/ERROR 分开，CI 应消费响应中的 outcome 和 ciExitCode。
     */
    @PostMapping("/gate-evaluations")
    ResponseEntity<GateEvaluationView> evaluate(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody EvaluateRequest request) {
        GateEvaluationView value = gates.evaluate(projectId, repositoryId, request.targetBranch(),
            request.ruleSetVersionId(), request.candidateJobId(), key);
        return ResponseEntity.created(URI.create(base(projectId, repositoryId) + "/gate-evaluations/" + value.id()))
            .body(value);
    }
    /** Viewer 读取已封存的历史门禁，不按当前基线或例外重算。 */
    @GetMapping("/gate-evaluations/{id}")
    GateEvaluationView gate(@PathVariable UUID projectId, @PathVariable UUID repositoryId, @PathVariable UUID id) {
        return gates.get(projectId, repositoryId, id);
    }
    /** Maintainer 创建有限范围/期限例外，具体匹配与生效区间约束由应用层检查。 */
    @PostMapping("/policy-exceptions")
    ResponseEntity<PolicyExceptionView> create(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @Valid @RequestBody CreateExceptionRequest request) {
        PolicyExceptionView value = exceptions.create(projectId, repositoryId, request.targetBranch(),
            request.ruleSetVersionId(), request.scopeType(), request.scopeValue(), request.reason(),
            request.effectiveAt(), request.expiresAt());
        return ResponseEntity.created(URI.create(base(projectId, repositoryId) + "/policy-exceptions/" + value.id()))
            .body(value);
    }
    /** 追加撤销信息，不删除例外创建事实，也不回写已完成门禁。 */
    @PostMapping("/policy-exceptions/{id}/revoke")
    PolicyExceptionView revoke(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable UUID id, @Valid @RequestBody RevokeRequest request) {
        return exceptions.revoke(projectId, repositoryId, id, request.reason());
    }
    /** 列出明确分支与规则版本的例外，状态按本次读取时间派生。 */
    @GetMapping("/policy-exceptions")
    List<PolicyExceptionView> list(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @RequestParam String targetBranch, @RequestParam UUID ruleSetVersionId) {
        return exceptions.list(projectId, repositoryId, targetBranch, ruleSetVersionId);
    }
    /** 在项目/仓库范围内读取例外及其可选撤销信息。 */
    @GetMapping("/policy-exceptions/{id}")
    PolicyExceptionView exception(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable UUID id) {
        return exceptions.get(projectId, repositoryId, id);
    }
    private static String base(UUID project, UUID repository) {
        return "/api/v1/projects/" + project + "/repositories/" + repository;
    }
    /** 分支/规则版本选择比较空间，candidateJobId 指定候选；幂等键在请求头中，不在 DTO 中。 */
    record EvaluateRequest(@NotBlank String targetBranch, @NotNull UUID ruleSetVersionId,
                           @NotNull UUID candidateJobId) { }
    /**
     * 分支/规则版本限定适用空间，scopeType/scopeValue 选择一个逻辑指纹或规则，reason 说明业务原因。
     * [effectiveAt, expiresAt) 为生效区间，应用层还校验未来到期、最长 30 天和原因长度。
     */
    record CreateExceptionRequest(@NotBlank String targetBranch, @NotNull UUID ruleSetVersionId,
            @NotNull PolicyExceptionScopeType scopeType, @NotBlank String scopeValue,
            @NotBlank String reason, @NotNull Instant effectiveAt, @NotNull Instant expiresAt) { }
    /** 撤销使用独立原因；应用层将同原因重放与不同原因冲突区分处理。 */
    record RevokeRequest(@NotBlank String reason) { }
}
