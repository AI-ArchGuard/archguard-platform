package io.github.aiarchguard.platform.governance;

import java.util.UUID;

/**
 * 治理模块公开的门禁应用边界：实现读取成功扫描及基线比较，按版本化策略和有效例外保存结论。
 * HTTP 与报告提交流程都复用此接口；当前主体由身份模块提供，资源 ID 本身不授予权限。
 */
public interface GateEvaluationOperations {
    /**
     * Maintainer 在明确比较空间内求值；Project 内同幂等键同输入重放原结果，不随基线/例外变化重算。
     * 同键异输入冲突；缺少基线可保存 ERROR/64，非成功任务或已捕获的比较错误保存 ERROR/70。
     */
    GateEvaluationView evaluate(UUID projectId, UUID repositoryId, String targetBranch,
                                UUID ruleSetVersionId, UUID candidateJobId, String idempotencyKey);
    /** Viewer 按项目、仓库和确切 ID 读取不可变历史，不能把旧 PASS 当成当前 PR head 的结论。 */
    GateEvaluationView get(UUID projectId, UUID repositoryId, UUID evaluationId);
}
