package io.github.aiarchguard.platform.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 已持久化、封存的基线比较视图，固定当次参考版本与候选报告，后续活动基线变化不会重新分类它。
 * 三项数量从同一份 findings 计算，只表达集合事实，是否阻断由独立门禁策略判断。
 *
 * @param id 比较事实 ID
 * @param baselineVersionId 当次使用的确切不可变基线版本
 * @param candidateJobId 已成功的候选扫描任务
 * @param candidateReportSha256 候选原始报告字节的摘要
 * @param fingerprintVersion 两侧集合共同采用的逻辑指纹算法版本
 * @param createdAt 比较创建时间
 * @param findings 已分类快照，存储读取按分类和指纹排序
 */
public record ComparisonView(UUID id, UUID baselineVersionId, UUID candidateJobId, String candidateReportSha256,
                             String fingerprintVersion, Instant createdAt, List<ClassifiedFinding> findings) {
    /** 固定列表内容，防止调用方通过传入的可变列表改变响应中的比较事实。 */
    public ComparisonView { findings = List.copyOf(findings); }
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
