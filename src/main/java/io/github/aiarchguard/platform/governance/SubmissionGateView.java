package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SubmissionGateView(UUID id, UUID submissionId, UUID projectId, UUID repositoryId,
                                 UUID ruleSetVersionId, GitRevision revision, UUID baselineVersionId,
                                 GateOutcome outcome, GateErrorKind errorKind, String errorCode,
                                 int ciExitCode, String policyVersion, String fingerprintVersion,
                                 Map<String, Long> counts, List<UUID> matchedExceptionVersionIds,
                                 Instant evaluatedAt) {
    public SubmissionGateView {
        counts = Map.copyOf(counts);
        matchedExceptionVersionIds = List.copyOf(matchedExceptionVersionIds);
    }
}
