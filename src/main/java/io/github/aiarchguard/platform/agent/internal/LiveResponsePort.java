package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.time.Duration;
import java.util.Set;

/** Separate real-response boundary; never eligible for the synthetic AgentModelPort worker. */
public interface LiveResponsePort {
    byte[] prepare(AgentModelPort.ModelInput input);
    Reply send(byte[] body, String apiKey, Duration remaining, Set<String> approvedModelIds);

    /** A rejected business output may still carry independently validated billable usage. */
    record Reply(String rawJson, String outputFailureCode, LiveTokenUsage usage,
            String providerResponseId, String actualModelId) {
        @Override public String toString() { return "Live response [content omitted]"; }
    }

    final class CallFailure extends RuntimeException {
        private final String code;
        public CallFailure(String code) {
            super(safe(code)); this.code = safe(code);
        }
        public String code() { return code; }
        private static String safe(String code) {
            return Set.of("MODEL_UNAVAILABLE", "MODEL_TIMEOUT", "OUTPUT_INVALID").contains(code == null ? "" : code)
                ? code : "MODEL_UNAVAILABLE";
        }
    }
}
