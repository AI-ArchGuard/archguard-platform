package io.github.aiarchguard.platform.agentcredential;

public interface CredentialOperations {
    void authorize(String origin, boolean secure, boolean mutation);
    void rejectInvalidWrite();
    CredentialStatus status();
    CredentialStatus write(String apiKey);
    CredentialStatus delete();
}
