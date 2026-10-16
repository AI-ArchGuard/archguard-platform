package io.github.aiarchguard.platform.agentcredential;

/** Internal module boundary: validated metadata only, never the decrypted API Key. */
public interface CredentialVersionSource {
    CredentialStatus current();

    /** The original execution budget; unsupported legacy sources must never use an unbounded fallback. */
    default CredentialStatus current(java.util.function.LongSupplier remainingNanos) {
        throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
    }
}
