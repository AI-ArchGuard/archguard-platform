package io.github.aiarchguard.platform.agent.infrastructure;

import java.time.Instant;

/** Public price snapshot, NOT owner approval or supplier invoice evidence. */
final class DeepSeekPriceSnapshot {
    static final String VERSION = io.github.aiarchguard.platform.agent.LiveCostPolicy.VERSION;
    private DeepSeekPriceSnapshot() { }

    static boolean valid(String version, Instant expiresAt, Instant now) {
        return VERSION.equals(version) && expiresAt != null && now != null && now.isBefore(expiresAt);
    }

    static long maxReservationMicrousd() { return peakCostMicrousd(8000, 1500, 0); }

    static long peakCostMicrousd(int inputTokens, int outputTokens, int cachedInputTokens) {
        try {
            return io.github.aiarchguard.platform.agent.LiveCostPolicy.cost(new io.github.aiarchguard.platform.agent.LiveTokenUsage(
                inputTokens, outputTokens, cachedInputTokens, inputTokens + outputTokens, 0));
        } catch (io.github.aiarchguard.platform.agent.AgentInvalidException invalid) {
            throw new IllegalArgumentException("Invalid token usage");
        }
    }
}
