package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.Classification;
import io.github.aiarchguard.platform.governance.ClassifiedFinding;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FindingClassifier {
    private FindingClassifier() { }

    static List<ClassifiedFinding> classify(List<FindingSnapshot> baseline, List<FindingSnapshot> candidate) {
        Map<String, FindingSnapshot> before = index(baseline);
        Map<String, FindingSnapshot> after = index(candidate);
        List<ClassifiedFinding> result = new ArrayList<>();
        for (FindingSnapshot current : candidate) {
            FindingSnapshot old = before.get(current.fingerprint());
            if (old != null && !old.payloadSha256().equals(current.payloadSha256())) {
                throw new InvalidGovernanceReportException("Finding fingerprint has conflicting canonical payloads");
            }
            result.add(item(old == null ? Classification.NEW : Classification.EXISTING, current));
        }
        for (FindingSnapshot old : baseline) {
            if (!after.containsKey(old.fingerprint())) result.add(item(Classification.RESOLVED, old));
        }
        result.sort(Comparator.comparing(ClassifiedFinding::classification)
            .thenComparing(ClassifiedFinding::fingerprint));
        return List.copyOf(result);
    }

    private static Map<String, FindingSnapshot> index(List<FindingSnapshot> snapshots) {
        Map<String, FindingSnapshot> result = new HashMap<>();
        for (FindingSnapshot value : snapshots) {
            if (result.putIfAbsent(value.fingerprint(), value) != null) {
                throw new InvalidGovernanceReportException("Duplicate logical Finding in report");
            }
        }
        return result;
    }
    private static ClassifiedFinding item(Classification classification, FindingSnapshot value) {
        return new ClassifiedFinding(classification, value.fingerprint(), value.payloadSha256(), value.ruleId(),
            value.ruleVersion(), value.severity(), value.scannerFindingId());
    }
}
