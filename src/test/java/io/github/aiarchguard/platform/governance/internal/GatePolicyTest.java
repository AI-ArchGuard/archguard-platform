package io.github.aiarchguard.platform.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aiarchguard.platform.governance.Classification;
import io.github.aiarchguard.platform.governance.ClassifiedFinding;
import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.GateOutcome;
import io.github.aiarchguard.platform.governance.PolicyExceptionScopeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatePolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");

    @Test void onlyUnexceptedNewHighAndCriticalBlockRegardlessOfOrder() {
        var newHigh = finding(Classification.NEW, "high", "a", "rule.a");
        var newCritical = finding(Classification.NEW, "critical", "b", "rule.b");
        var oldHigh = finding(Classification.EXISTING, "high", "c", "rule.c");
        var resolvedCritical = finding(Classification.RESOLVED, "critical", "d", "rule.d");
        var low = finding(Classification.NEW, "low", "e", "rule.e");
        var one = GatePolicy.evaluate(comparison(List.of(newHigh, oldHigh, low, newCritical, resolvedCritical)),
            List.of(), NOW);
        var two = GatePolicy.evaluate(comparison(List.of(resolvedCritical, newCritical, low, oldHigh, newHigh)),
            List.of(), NOW);
        assertThat(one).isEqualTo(two);
        assertThat(one.outcome()).isEqualTo(GateOutcome.FAIL);
        assertThat(one.ciExitCode()).isEqualTo(2);
        assertThat(one.blockedCount()).isEqualTo(2);
    }

    @Test void effectiveExceptionPassesThenExpiryOrRevocationReblocks() {
        var finding = finding(Classification.NEW, "high", "a", "rule.a");
        var exception = exception(PolicyExceptionScopeType.FINGERPRINT, "a", NOW.minusSeconds(1),
            NOW.plusSeconds(60), null);
        var comparison = comparison(List.of(finding));
        var active = GatePolicy.evaluate(comparison, List.of(exception), NOW);
        assertThat(active.outcome()).isEqualTo(GateOutcome.PASS);
        assertThat(active.matchedExceptionVersionIds()).containsExactly(exception.id());
        var expired = GatePolicy.evaluate(comparison, List.of(exception), NOW.plusSeconds(60));
        assertThat(expired.outcome()).isEqualTo(GateOutcome.FAIL);
        assertThat(expired.expiredExceptionCount()).isEqualTo(1);
        var revoked = exception(PolicyExceptionScopeType.RULE, "rule.a", NOW.minusSeconds(1),
            NOW.plusSeconds(60), NOW);
        assertThat(GatePolicy.evaluate(comparison, List.of(revoked), NOW).outcome()).isEqualTo(GateOutcome.FAIL);
    }

    @Test void ruleScopedExceptionMatchesOnlyItsRuleAndPendingExceptionDoesNotMatch() {
        var matching = exception(PolicyExceptionScopeType.RULE, "rule.a", NOW.minusSeconds(1),
            NOW.plusSeconds(60), null);
        var pending = exception(PolicyExceptionScopeType.FINGERPRINT, "b", NOW.plusSeconds(1),
            NOW.plusSeconds(60), null);
        var result = GatePolicy.evaluate(comparison(List.of(finding(Classification.NEW, "critical", "a", "rule.a"),
            finding(Classification.NEW, "critical", "b", "rule.b"))), List.of(pending, matching), NOW);
        assertThat(result.blockedCount()).isEqualTo(1);
        assertThat(result.matchedExceptionVersionIds()).containsExactly(matching.id());
    }

    private static ComparisonView comparison(List<ClassifiedFinding> findings) {
        return new ComparisonView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
            "platform-finding-v1", NOW, findings);
    }
    private static ClassifiedFinding finding(Classification classification, String severity,
            String fingerprint, String rule) {
        return new ClassifiedFinding(classification, fingerprint, "p".repeat(64), rule, "0.1.0", severity, "scanner");
    }
    private static PolicyExceptionRecord exception(PolicyExceptionScopeType type, String value,
            Instant effective, Instant expiry, Instant revoked) {
        return new PolicyExceptionRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "main",
            UUID.randomUUID(), type, value, "Reviewed waiver", effective, expiry, UUID.randomUUID(), NOW,
            revoked == null ? null : UUID.randomUUID(), revoked, revoked == null ? null : "Reason for revocation",
            revoked == null ? null : UUID.randomUUID());
    }
}
