package io.github.aiarchguard.platform.agentcredential;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Trusted internal boundary, not a credential getter or an HTTP API.
 * The callback runs synchronously on the calling thread and must not retain or log the Key,
 * enter another module, schedule later Key use, or reenter credential operations.
 * A current version is checked under the same process-local lock used by replacement and deletion.
 */
public interface CredentialUseSource {
    void consume(UUID expectedVersion, Consumer<String> finalOperation);
}
