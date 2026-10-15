package io.github.aiarchguard.platform.agentcredential;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Trusted internal boundary, not a credential getter or an HTTP API.
 * The callback runs synchronously on the calling thread and must not retain or log the Key,
 * enter another module, schedule later Key use, or reenter credential operations.
 * A current version is checked under the same process-local lock used by replacement and deletion.
 */
public interface CredentialUseSource {
    void consume(UUID expectedVersion, Consumer<String> finalOperation);

    /**
     * Remaining time from the original monotonic deadline, at most 30 seconds.
     * The trusted supplier must only read that clock/interruption state: no identity,
     * audit, database or other module calls while the credential slot may be locked.
     * Legacy implementations fail closed rather than falling back to unbounded use.
     */
    default void consume(UUID expectedVersion, LongSupplier remainingNanos, Consumer<String> finalOperation) {
        throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
    }
}
