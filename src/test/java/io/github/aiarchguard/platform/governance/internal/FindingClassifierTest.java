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

class FindingClassifierTest {
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

    @Test void sameFingerprintWithDifferentPayloadFailsClosed() {
        assertThatThrownBy(() -> FindingClassifier.classify(
            List.of(finding("same", "one")), List.of(finding("same", "two"))))
            .isInstanceOf(InvalidGovernanceReportException.class);
    }

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
