package io.github.aiarchguard.platform.agentcredential.internal;

import io.github.aiarchguard.platform.agentcredential.CredentialUseSource;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
final class StoredCredentialUseSource implements CredentialUseSource {
    private final CredentialApplicationService credentials;
    StoredCredentialUseSource(CredentialApplicationService credentials) { this.credentials = credentials; }
    @Override public void consume(UUID expectedVersion, Consumer<String> finalOperation) {
        credentials.internalConsume(expectedVersion, finalOperation);
    }
}
