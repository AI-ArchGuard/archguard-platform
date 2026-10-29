package io.github.aiarchguard.platform.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.governance.Classification;
import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 用小型内存快照验证基线／候选集合分类、冲突拒绝及 API 计数，不涉及数据库。 */
class FindingClassifierTest {
    /** 输入顺序不改变集合成员与输出排序：候选独有为新增，交集为存量，基线独有为已解决。 */
    @Test void setDifferenceIsStableAcrossInputOrder() {
        FindingSnapshot a = finding("a", "payload-a");
        FindingSnapshot b = finding("b", "payload-b");
        FindingSnapshot c = finding("c", "payload-c");
        var first = FindingClassifier.classify(List.of(a, b), List.of(b, c));
        assertThat(first).isEqualTo(FindingClassifier.classify(List.of(b, a), List.of(c, b)));
        assertThat(first).extracting(value -> value.classification())
            .containsExactly(Classification.NEW, Classification.EXISTING, Classification.RESOLVED);
        assertThat(first).extracting(value -> value.fingerprint()).containsExactly("c", "b", "a");
    }

    /** 相同指纹对应不同规范载荷不能当成同一个问题静默合并。 */
    @Test void sameFingerprintWithDifferentPayloadFailsClosed() {
        assertThatThrownBy(() -> FindingClassifier.classify(
            List.of(finding("same", "one")), List.of(finding("same", "two"))))
            .isInstanceOf(InvalidGovernanceReportException.class);
    }

    /** 比较对象序列化后的三类计数应与实际集合一致，供 Web 直接消费。 */
    @Test void comparisonCountsAreExposedInTheApiResponse() {
        var items = FindingClassifier.classify(List.of(finding("a", "a")),
            List.of(finding("b", "b")));
        var view = new ComparisonView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "a".repeat(64), "platform-finding-v1", Instant.EPOCH, items);
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(view);
        assertThat(json.path("newCount").asInt()).isEqualTo(1);
        assertThat(json.path("existingCount").asInt()).isZero();
        assertThat(json.path("resolvedCount").asInt()).isEqualTo(1);
    }

    private static FindingSnapshot finding(String fingerprint, String payload) {
        return new FindingSnapshot(fingerprint, payload, "rule", "0.1.0", "high", "scanner");
    }
}
