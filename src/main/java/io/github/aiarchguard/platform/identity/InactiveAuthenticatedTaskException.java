package io.github.aiarchguard.platform.identity;

/** Stable failure for an absent, expired or already-consumed authenticated task scope. */
public final class InactiveAuthenticatedTaskException extends IllegalStateException {
    public InactiveAuthenticatedTaskException() {
        super("An active authenticated OIDC task is required");
    }
}
