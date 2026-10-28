package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 真实执行链的有界数据库事务端口；共享原单调期限，失败和费用清理另有有限预算，回调中禁止 HTTP 或明文凭据消费。
 * 默认实现拒绝无界回退。
 */
public interface LiveDatabaseWork {
    /** Same original monotonic remaining budget, not a new per-operation 30-second allowance. */
    default <T> T transaction(LongSupplier remainingNanos, boolean readOnly, Supplier<T> action) {
        throw new AgentUnavailableException();
    }

    /** Independent finite finalization budget; never inherit an expired send deadline. */
    default <T> T cleanup(Supplier<T> action) {
        throw new AgentUnavailableException();
    }
}
