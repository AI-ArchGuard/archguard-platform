package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.LiveBatchInput;
import io.github.aiarchguard.platform.agent.LiveBatchView;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

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
    /** Bounded admission must not fall back to the unbounded legacy transaction. */
    default Admission reserve(UUID project, UUID batch, UUID template, UUID request, LongSupplier remainingNanos) {
        throw new io.github.aiarchguard.platform.agent.AgentUnavailableException();
    }
    /** Fresh, non-mutating admission after a unique reservation; not a Token/network permit. */
    SendApproval checkBeforeSend(UUID project, UUID batch, UUID template, UUID request);
    /** Reuse the original execution deadline, including database lock waits. */
    default SendApproval checkBeforeSend(UUID project, UUID batch, UUID template, UUID request, LongSupplier remainingNanos) {
        throw new io.github.aiarchguard.platform.agent.AgentUnavailableException();
    }
    Outcome settle(UUID project, UUID request, String priceVersion, LiveTokenUsage usage);
    Outcome unknown(UUID project, UUID request);
    /** Independent append-only stop; never releases an attempt or authorizes replacement calls. */
    default void stopBatch(UUID project, UUID batch, UUID request, String code) {
        throw new io.github.aiarchguard.platform.agent.AgentUnavailableException();
    }
    record Admission(UUID attemptId, long reservedMicrousd, boolean newAttempt) { }
    record Outcome(String state, Long actualMicrousd) { }
    record SendApproval(UUID credentialVersion, java.util.Set<String> allowedResponseModels) {
        public SendApproval { allowedResponseModels = java.util.Set.copyOf(allowedResponseModels); }
    }
}
