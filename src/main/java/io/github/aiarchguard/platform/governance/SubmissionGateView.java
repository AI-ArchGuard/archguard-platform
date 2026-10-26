package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 面向 CI 的报告门禁响应，把完成提交的 Git 修订与确切历史门禁拼接起来，不重新求值。
 * 即使这份报告迟到，也可读其历史结论；只有与可信当前 head 一致的结果才能作为当前 PR 门禁关联。
 *
 * @param id 不可变门禁 ID
 * @param submissionId 该门禁关联的报告提交 ID
 * @param projectId 所属项目
 * @param repositoryId 所属仓库
 * @param ruleSetVersionId 提交绑定的规则版本
 * @param revision 提交对应的 Git 修订断言
 * @param baselineVersionId 门禁当时的确切基线版本，ERROR 可能为空
 * @param outcome PASS、FAIL 或 ERROR
 * @param errorKind ERROR 的稳定类别，正常结论为空
 * @param errorCode ERROR 的稳定错误代码，正常结论为空
 * @param ciExitCode 最终 CI 映射：PASS/0、FAIL/2、配置 ERROR/64、其他 ERROR/70
 * @param policyVersion 求值时策略版本
 * @param fingerprintVersion 求值时逻辑指纹算法版本
 * @param counts NEW、EXISTING、RESOLVED 的基线比较数量，不是 PR 修订差异
 * @param matchedExceptionVersionIds 求值时命中的不可变例外版本
 * @param evaluatedAt 门禁求值与例外判断的时间
 */
public record SubmissionGateView(UUID id, UUID submissionId, UUID projectId, UUID repositoryId,
                                 UUID ruleSetVersionId, GitRevision revision, UUID baselineVersionId,
                                 GateOutcome outcome, GateErrorKind errorKind, String errorCode,
                                 int ciExitCode, String policyVersion, String fingerprintVersion,
                                 Map<String, Long> counts, List<UUID> matchedExceptionVersionIds,
                                 Instant evaluatedAt) {
    /** 复制分类计数和例外命中集合，避免外部可变容器改变已返回的历史视图。 */
    public SubmissionGateView {
        counts = Map.copyOf(counts);
        matchedExceptionVersionIds = List.copyOf(matchedExceptionVersionIds);
    }
}
