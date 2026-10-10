package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.LiveBatchInput;
import io.github.aiarchguard.platform.agent.LiveBatchView;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.util.List;
import java.util.UUID;

/** Internal orchestration contract; deliberately has no HTTP or model-port binding. */
public interface LiveAccountingOperations {
    String MODEL_PROFILE = "deepseek-personal-0.1.0";
    String EXPLANATION_PROMPT = "finding-explanation-deepseek-0.1.0";
    String SUMMARY_PROMPT = "pr-summary-deepseek-0.1.0";
    String preview(UUID project, List<UUID> templates);
    LiveBatchView approve(UUID project, String origin, boolean secure, LiveBatchInput input);
    LiveBatchView get(UUID project, UUID batch);
    LiveBatchView revoke(UUID project, UUID batch, String origin, boolean secure);
    String requestDigest(UUID project, UUID batch, UUID template);
    Admission reserve(UUID project, UUID batch, UUID template, UUID request);
    Outcome settle(UUID project, UUID request, String priceVersion, LiveTokenUsage usage);
    Outcome unknown(UUID project, UUID request);
    record Admission(UUID attemptId, long reservedMicrousd, boolean newAttempt) { }
    record Outcome(String state, Long actualMicrousd) { }
}
