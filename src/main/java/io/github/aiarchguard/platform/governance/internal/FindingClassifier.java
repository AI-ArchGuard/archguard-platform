package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.Classification;
import io.github.aiarchguard.platform.governance.ClassifiedFinding;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对两个已使用同一算法的逻辑 Finding 快照做纯集合分类，供基线应用服务保存比较事实。
 * 只认逻辑指纹；Scanner ID、行号和报告遍历顺序不决定集合身份。
 */
final class FindingClassifier {
    private FindingClassifier() { }

    /**
     * 计算 NEW=C−B、EXISTING=C∩B、RESOLVED=B−C，返回按分类和指纹排序的只读列表。
     * 新增/存续项保留候选的严重性及追溯 ID，已解决项保留基线值；无数据库或审计副作用。
     */
    static List<ClassifiedFinding> classify(List<FindingSnapshot> baseline, List<FindingSnapshot> candidate) {
        Map<String, FindingSnapshot> before = index(baseline);
        Map<String, FindingSnapshot> after = index(candidate);
        List<ClassifiedFinding> result = new ArrayList<>();
        for (FindingSnapshot current : candidate) {
            FindingSnapshot old = before.get(current.fingerprint());
            // 同哈希而规范载荷摘要不同不能当成同一问题，必须暴露契约冲突。
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

    /** 每份输入须是集合；重复逻辑 Finding 即使载荷相同也拒绝，避免悄悄去重掩盖报告错误。 */
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
