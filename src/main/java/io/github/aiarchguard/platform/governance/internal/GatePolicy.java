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

/**
 * 默认版本化门禁的纯计算器，消费不可变比较与同空间例外；不修改 Finding、基线或历史结果。
 * 只有未获有效例外的 NEW high/critical 会阻断，存量债务与已解决项只保留统计意义。
 */
final class GatePolicy {
    static final String VERSION = "default-new-high-critical-v1";
    private GatePolicy() { }

    /** 用同一求值时间判断全部例外，返回结论、CI 码及去重排序的命中版本，无持久化副作用。 */
    static GateDecision evaluate(ComparisonView comparison, List<PolicyExceptionRecord> exceptions, Instant at) {
        long blocked = 0;
        Set<UUID> matched = new HashSet<>();
        for (var finding : comparison.findings()) {
            if (finding.classification() != Classification.NEW
                || !("high".equals(finding.severity()) || "critical".equals(finding.severity()))) continue;
            boolean excepted = false;
            // 不在首个命中处停止：所有匹配版本都要留下，便于历史门禁追溯。
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
        // 这是输入列表中过期例外的计数，不改变例外状态，也不触发到期写入。
        long expired = exceptions.stream().filter(value -> !at.isBefore(value.expiresAt())).count();
        return new GateDecision(blocked == 0 ? GateOutcome.PASS : GateOutcome.FAIL, blocked == 0 ? 0 : 2,
            blocked, ordered, expired);
    }
}
