package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GateOutcome;
import java.util.List;
import java.util.UUID;

/**
 * 默认策略传给门禁应用服务的计算结果；命中列表复制为只读，避免后续修改改变这次决策。
 * expiredExceptionCount 仅是计算附带统计，当前应用服务没有把它写入 GateEvaluation。
 */
record GateDecision(GateOutcome outcome, int ciExitCode, long blockedCount,
                    List<UUID> matchedExceptionVersionIds, long expiredExceptionCount) {
    GateDecision { matchedExceptionVersionIds = List.copyOf(matchedExceptionVersionIds); }
}
