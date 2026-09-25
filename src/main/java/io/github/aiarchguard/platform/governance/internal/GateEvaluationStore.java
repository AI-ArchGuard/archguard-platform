package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GateEvaluationView;
import java.util.Optional;
import java.util.UUID;

public interface GateEvaluationStore {
    record Stored(GateEvaluationView view, String requestSha256) { }
    Optional<Stored> byKey(UUID projectId, String key);
    Optional<GateEvaluationView> find(UUID projectId, UUID repositoryId, UUID id);
    boolean insert(GateEvaluationView value, String key, String requestSha256, UUID actorId);
    void seal(UUID id);
}
