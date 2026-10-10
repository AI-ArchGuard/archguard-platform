package io.github.aiarchguard.platform.agent;

/** Frozen 2026-10-06 peak snapshot; neither live price discovery nor a supplier invoice. */
public final class LiveCostPolicy {
    public static final String VERSION = "deepseek-flash-peak-usd-2026-10-06";
    public static final long RESERVATION = 4200;
    private LiveCostPolicy() { }
    public static long cost(LiveTokenUsage usage) {
        if (usage == null || usage.inputTokens() < 0 || usage.inputTokens() > 8000
                || usage.outputTokens() < 0 || usage.outputTokens() > 1500 || usage.cachedInputTokens() < 0
                || usage.cachedInputTokens() > usage.inputTokens() || usage.reasoningTokens() != 0
                || usage.totalTokens() != usage.inputTokens() + usage.outputTokens()) throw new AgentInvalidException();
        long numerator = (usage.inputTokens() - usage.cachedInputTokens()) * 300_000L
            + usage.cachedInputTokens() * 6_000L + usage.outputTokens() * 1_200_000L;
        return (numerator + 999_999L) / 1_000_000L;
    }
}
