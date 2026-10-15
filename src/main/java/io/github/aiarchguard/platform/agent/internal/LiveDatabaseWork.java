package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Internal database work only; no callback may send HTTP or consume a plaintext credential. */
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
