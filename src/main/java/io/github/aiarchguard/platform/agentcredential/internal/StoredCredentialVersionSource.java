package io.github.aiarchguard.platform.agentcredential.internal;

import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import org.springframework.stereotype.Component;

@Component
final class StoredCredentialVersionSource implements CredentialVersionSource {
    private final CredentialApplicationService credentials;
    StoredCredentialVersionSource(CredentialApplicationService credentials) { this.credentials = credentials; }
    @Override public CredentialStatus current() { return credentials.internalStatus(); }
}
