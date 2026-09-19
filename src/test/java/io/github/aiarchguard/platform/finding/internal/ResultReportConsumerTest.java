package io.github.aiarchguard.platform.finding.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.finding.InvalidResultException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResultReportConsumerTest {
    private final CapturingStore store = new CapturingStore();
    private final ResultReportConsumer consumer = new ResultReportConsumer(new ObjectMapper(), store);

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

    @Test
    void rejectsIdentityMismatchBeforePersisting() throws IOException {
        byte[] report = getClass().getResourceAsStream("/reports/full-report.json").readAllBytes();
        assertThatThrownBy(() -> consumer.validateAndStore(UUID.randomUUID(), UUID.randomUUID(),
            "platform:wrong:identity", "0.2.1", report))
            .isInstanceOf(InvalidResultException.class)
            .hasMessageContaining("identity");
        assertThat(store.findings).isEmpty();
    }

    @Test
    void rejectsUnknownFieldsThroughPinnedSchema() throws IOException {
        String report = new String(getClass().getResourceAsStream("/reports/full-report.json").readAllBytes())
            .replaceFirst("\\{", "{\\\"unknown\\\":true,");
        assertThatThrownBy(() -> consumer.validateAndStore(UUID.randomUUID(), UUID.randomUUID(),
            "example/order-service", "0.2.1", report.getBytes()))
            .isInstanceOf(InvalidResultException.class)
            .hasMessageContaining("Schema 0.1.0");
    }

    private static final class CapturingStore implements FindingIngestionStore {
        private List<NormalizedEvidence> evidences = List.of();
        private List<NormalizedFinding> findings = List.of();
        @Override public void store(UUID jobId, List<NormalizedEvidence> evidences, List<NormalizedFinding> findings) {
            this.evidences=evidences; this.findings=findings;
        }
    }
}
