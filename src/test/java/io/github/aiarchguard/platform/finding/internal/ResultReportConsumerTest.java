package io.github.aiarchguard.platform.finding.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.finding.InvalidResultException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 用固定合成报告检查结果消费入口的正常解析及拒绝分支。
 * 写入端用内存捕获器替代；本类不启动 Runner，也不验证真实数据库事务。
 */
class ResultReportConsumerTest {
    private final CapturingStore store = new CapturingStore();
    private final ResultReportConsumer consumer = new ResultReportConsumer(new ObjectMapper(), store);

    /** 检查接受后的版本、摘要长度，以及规范化 Finding／Evidence 的数量和关联。 */
    @Test
    void validatesIdentityReferencesAndNormalizesFindingsAndEvidence() throws IOException {
        byte[] report = getClass().getResourceAsStream("/reports/full-report.json").readAllBytes();
        var result = consumer.validateAndStore(UUID.randomUUID(), UUID.randomUUID(),
            "example/order-service", "0.2.1", report);
        assertThat(result.schemaVersion()).isEqualTo("0.1.0");
        assertThat(result.sha256()).hasSize(64);
        assertThat(store.evidences).hasSize(2);
        assertThat(store.findings).hasSize(2);
        assertThat(store.findings.getFirst().evidenceIds()).hasSize(1);
    }

    /** 报告身份不匹配时拒绝，并确认尚未把 Finding 交给写入端。 */
    @Test
    void rejectsIdentityMismatchBeforePersisting() throws IOException {
        byte[] report = getClass().getResourceAsStream("/reports/full-report.json").readAllBytes();
        assertThatThrownBy(() -> consumer.validateAndStore(UUID.randomUUID(), UUID.randomUUID(),
            "platform:wrong:identity", "0.2.1", report))
            .isInstanceOf(InvalidResultException.class)
            .hasMessageContaining("identity");
        assertThat(store.findings).isEmpty();
    }

    /** 向固定报告加入额外字段形式的内容，确认固定 Schema 的拒绝路径保持生效。 */
    @Test
    void rejectsUnknownFieldsThroughPinnedSchema() throws IOException {
        String report = new String(getClass().getResourceAsStream("/reports/full-report.json").readAllBytes())
            .replaceFirst("\\{", "{\\\"unknown\\\":true,");
        assertThatThrownBy(() -> consumer.validateAndStore(UUID.randomUUID(), UUID.randomUUID(),
            "example/order-service", "0.2.1", report.getBytes()))
            .isInstanceOf(InvalidResultException.class)
            .hasMessageContaining("Schema 0.1.0");
    }

    /** 只保存本次传入的列表，便于观察消费器输出；不模拟唯一约束或并发行为。 */
    private static final class CapturingStore implements FindingIngestionStore {
        private List<NormalizedEvidence> evidences = List.of();
        private List<NormalizedFinding> findings = List.of();
        @Override public void store(UUID jobId, List<NormalizedEvidence> evidences, List<NormalizedFinding> findings) {
            this.evidences=evidences; this.findings=findings;
        }
    }
}
