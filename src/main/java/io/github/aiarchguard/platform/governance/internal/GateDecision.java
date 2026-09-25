package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GateOutcome;
import java.util.List;
import java.util.UUID;

record GateDecision(GateOutcome outcome, int ciExitCode, long blockedCount,
                    List<UUID> matchedExceptionVersionIds, long expiredExceptionCount) {
    GateDecision { matchedExceptionVersionIds = List.copyOf(matchedExceptionVersionIds); }
}
