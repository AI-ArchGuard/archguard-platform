package io.github.aiarchguard.platform.agent.internal;

import java.time.Duration;

/** Infrastructure-owned lazy fixed-protocol resources; no plaintext getter or endpoint override. */
public interface LiveAdapterBundle extends AutoCloseable {
    LiveResponsePort responses();
    LiveExecutionCoordinator.CredentialUse credentials();
    @Override void close();
    boolean awaitTermination(Duration timeout) throws InterruptedException;
}
