package io.github.aiarchguard.platform.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * 供 Web 解释 PR 内变化的只读修订差异，与基线门禁使用不同参考点，永不参与门禁求值。
 * 前驱来自实际应用的验签事件中前一个不同 head，而不是报告到达顺序，也不推导 Git 祖先关系。
 *
 * @param reference 参考类型，当前为 PR_PREVIOUS_REVISION
 * @param availability AVAILABLE 或明确不可用原因；不可用时空 findings 不表示没有问题
 * @param currentHeadSha 当前已验证 head
 * @param previousHeadSha 前一个不同可信 head，缺少历史时为空
 * @param currentGateEvaluationId 对应当前候选的确切门禁 ID，尚不可用时可能为空
 * @param previousGateEvaluationId 对应前驱候选的确切门禁 ID，尚不可用时可能为空
 * @param ruleSetVersionId 两侧候选须共有的规则版本
 * @param fingerprintVersion 两侧共同的逻辑指纹版本，不可用时为空
 * @param findings 完整候选集合之间的三类差异，不能冒充基线比较中的 NEW/RESOLVED
 */
public record PrRevisionDeltaView(String reference, String availability, String currentHeadSha,
                                  String previousHeadSha, UUID currentGateEvaluationId,
                                  UUID previousGateEvaluationId, UUID ruleSetVersionId,
                                  String fingerprintVersion, List<ClassifiedFinding> findings) {
    /** 固定结果列表，三项计数都从同一份修订差异快照派生。 */
    public PrRevisionDeltaView { findings = List.copyOf(findings); }
    @JsonProperty("newCount") public long newCount() {
        return findings.stream().filter(f -> f.classification() == Classification.NEW).count();
    }
    @JsonProperty("existingCount") public long existingCount() {
        return findings.stream().filter(f -> f.classification() == Classification.EXISTING).count();
    }
    @JsonProperty("resolvedCount") public long resolvedCount() {
        return findings.stream().filter(f -> f.classification() == Classification.RESOLVED).count();
    }
}
