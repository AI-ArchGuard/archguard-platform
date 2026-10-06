package io.github.aiarchguard.platform.agent.infrastructure;

import java.time.Instant;

/** Public price snapshot, NOT owner approval or supplier invoice evidence. */
final class DeepSeekPriceSnapshot {
    static final String VERSION = "deepseek-flash-peak-usd-2026-10-06";
    private DeepSeekPriceSnapshot() { }

    static boolean valid(String version, Instant expiresAt, Instant now) {
        return VERSION.equals(version) && expiresAt != null && now != null && now.isBefore(expiresAt);
    }

    static long maxReservationMicrousd() { return peakCostMicrousd(8000, 1500, 0); }

    static long peakCostMicrousd(int inputTokens, int outputTokens, int cachedInputTokens) {
        if (inputTokens < 0 || inputTokens > 8000 || outputTokens < 0 || outputTokens > 1500
                || cachedInputTokens < 0 || cachedInputTokens > inputTokens) throw new IllegalArgumentException("Invalid token usage");
        // USD / million tokens: cache miss 0.30, cache hit 0.006, output 1.20.
        long numerator = (inputTokens - cachedInputTokens) * 300_000L
            + cachedInputTokens * 6_000L + outputTokens * 1_200_000L;
        return (numerator + 999_999L) / 1_000_000L;
    }
}
