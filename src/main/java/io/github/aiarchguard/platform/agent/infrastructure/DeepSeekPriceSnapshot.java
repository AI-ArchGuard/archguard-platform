package io.github.aiarchguard.platform.agent.infrastructure;

import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.time.Instant;

/** Public price snapshot, NOT owner approval or supplier invoice evidence. */
final class DeepSeekPriceSnapshot {
    static final String VERSION = LiveCostPolicy.VERSION;
    private DeepSeekPriceSnapshot() { }

    static boolean valid(String version, Instant expiresAt, Instant now) {
        return LiveCostPolicy.supported(version) && expiresAt != null && now != null && now.isBefore(expiresAt);
    }

    static long maxReservationMicrousd() { return peakCostMicrousd(8000, 1500, 0); }

    static long maxReservationMicrousd(String version) {
        try { return LiveCostPolicy.reservation(version); }
        catch (AgentInvalidException invalid) { throw new IllegalArgumentException("Invalid price version"); }
    }

    static long peakCostMicrousd(String version, int inputTokens, int outputTokens, int cachedInputTokens) {
        try {
            return LiveCostPolicy.cost(version, new LiveTokenUsage(inputTokens, outputTokens, cachedInputTokens,
                Math.addExact(inputTokens, outputTokens), 0));
        } catch (AgentInvalidException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid token usage");
        }
    }

    static long peakCostMicrousd(int inputTokens, int outputTokens, int cachedInputTokens) {
        return peakCostMicrousd(VERSION, inputTokens, outputTokens, cachedInputTokens);
    }
}
