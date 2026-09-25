package io.github.aiarchguard.platform.governance;

import java.util.UUID;

public interface GateEvaluationOperations {
    GateEvaluationView evaluate(UUID projectId, UUID repositoryId, String targetBranch,
                                UUID ruleSetVersionId, UUID candidateJobId, String idempotencyKey);
    GateEvaluationView get(UUID projectId, UUID repositoryId, UUID evaluationId);
}
