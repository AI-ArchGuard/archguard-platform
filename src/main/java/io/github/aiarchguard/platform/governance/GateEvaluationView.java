package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GateEvaluationView(UUID id, UUID projectId, UUID repositoryId, String targetBranch,
                                 UUID ruleSetVersionId, UUID candidateJobId, UUID comparisonId,
                                 UUID baselineVersionId, GateOutcome outcome, int ciExitCode,
                                 GateErrorKind errorKind, String errorCode, String policyVersion,
                                 String fingerprintVersion, long newCount, long existingCount,
                                 long resolvedCount, long blockedCount,
                                 List<UUID> matchedExceptionVersionIds, Instant evaluatedAt) {
    public GateEvaluationView { matchedExceptionVersionIds = List.copyOf(matchedExceptionVersionIds); }
}
