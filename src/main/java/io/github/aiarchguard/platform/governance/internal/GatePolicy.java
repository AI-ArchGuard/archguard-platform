package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.Classification;
import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.GateOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class GatePolicy {
    static final String VERSION = "default-new-high-critical-v1";
    private GatePolicy() { }

    static GateDecision evaluate(ComparisonView comparison, List<PolicyExceptionRecord> exceptions, Instant at) {
        long blocked = 0;
        Set<UUID> matched = new HashSet<>();
        for (var finding : comparison.findings()) {
            if (finding.classification() != Classification.NEW
                || !("high".equals(finding.severity()) || "critical".equals(finding.severity()))) continue;
            boolean excepted = false;
            for (PolicyExceptionRecord exception : exceptions) {
                if (exception.validAt(at) && exception.matches(finding)) {
                    matched.add(exception.id());
                    excepted = true;
                }
            }
            if (!excepted) blocked++;
        }
        List<UUID> ordered = new ArrayList<>(matched);
        ordered.sort(Comparator.comparing(UUID::toString));
        long expired = exceptions.stream().filter(value -> !at.isBefore(value.expiresAt())).count();
        return new GateDecision(blocked == 0 ? GateOutcome.PASS : GateOutcome.FAIL, blocked == 0 ? 0 : 2,
            blocked, ordered, expired);
    }
}
