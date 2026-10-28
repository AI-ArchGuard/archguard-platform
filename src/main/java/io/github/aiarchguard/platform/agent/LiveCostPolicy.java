package io.github.aiarchguard.platform.agent;

/**
 * 按不可变高峰价格快照校验用量并计算 microUSD。旧策略与上下文策略分别保留预留规则，不动态查询价格或冒充供应商账单。
 */
public final class LiveCostPolicy {
    public static final String VERSION = "deepseek-flash-peak-usd-2026-10-06";
    public static final long RESERVATION = 4200;
    public static final String CONTEXT_VERSION = "deepseek-flash-context-peak-usd-2026-10-15";
    public static final long CONTEXT_RESERVATION = 316373;
    public static final int CONTEXT_INPUT_LIMIT = 1048576;
    public static final int POSTCALL_INPUT_THRESHOLD = 8000;
    public static final long PER_REQUEST_LIMIT = 350000;
    public static final long BATCH_LIMIT = 700000;
    public static final String CONTEXT_MODEL_PROFILE = "deepseek-personal-0.2.0";
    private LiveCostPolicy() { }
    public static boolean supported(String version) { return VERSION.equals(version) || CONTEXT_VERSION.equals(version); }
    public static boolean isContext(String version) {
        if (!supported(version)) throw new AgentInvalidException();
        return CONTEXT_VERSION.equals(version);
    }
    public static String modelProfile(String version) {
        return isContext(version) ? CONTEXT_MODEL_PROFILE : "deepseek-personal-0.1.0";
    }
    public static long reservation(String version) {
        return isContext(version) ? CONTEXT_RESERVATION : RESERVATION;
    }
    /** 根据策略校验用量边界，合计缓存命中/未命中输入和输出，再统一向上舍入到 microUSD，避免逐项舍入扩大误差。 */
    public static long cost(String version, LiveTokenUsage usage) {
        if (!isContext(version)) return cost(usage);
        if (usage == null || usage.inputTokens() < 0 || usage.inputTokens() > CONTEXT_INPUT_LIMIT
                || usage.outputTokens() < 0 || usage.outputTokens() > 1500 || usage.cachedInputTokens() < 0
                || usage.cachedInputTokens() > usage.inputTokens() || usage.reasoningTokens() != 0
                || usage.totalTokens() != (long) usage.inputTokens() + usage.outputTokens()) throw new AgentInvalidException();
        // Bounds above keep every product far below Long.MAX_VALUE; total comparison never wraps an int.
        long numerator = (usage.inputTokens() - (long) usage.cachedInputTokens()) * 300_000L
            + usage.cachedInputTokens() * 6_000L + usage.outputTokens() * 1_200_000L;
        return (numerator + 999_999L) / 1_000_000L;
    }
    /** 保留旧 8,000 输入上限的计算语义；历史结算不随新上下文策略升级。 */
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
