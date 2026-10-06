package io.github.aiarchguard.platform.agentcredential;

import java.time.Instant;
import java.util.UUID;

/** Write-only credential management returns metadata, never a secret or fingerprint. */
public record CredentialStatus(boolean configured, UUID credentialVersion, Instant updatedAt) {
    public static CredentialStatus absent() { return new CredentialStatus(false, null, null); }
}
