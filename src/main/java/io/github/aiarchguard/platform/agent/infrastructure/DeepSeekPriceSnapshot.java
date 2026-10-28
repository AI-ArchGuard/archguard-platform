package io.github.aiarchguard.platform.agent.infrastructure;

import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.time.Instant;

/**
 * 不可变公开价格快照的基础设施门面，委托领域费用策略计算预留及实际费用；不是 owner 批准、实时价格发现或供应商发票。
 */
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
