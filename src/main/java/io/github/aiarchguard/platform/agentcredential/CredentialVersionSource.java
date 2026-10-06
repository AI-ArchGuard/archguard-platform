package io.github.aiarchguard.platform.agentcredential;

/** Internal module boundary: validated metadata only, never the decrypted API Key. */
public interface CredentialVersionSource {
    CredentialStatus current();
}
