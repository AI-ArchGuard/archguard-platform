package io.github.aiarchguard.platform.identity;

import java.util.UUID;

/** In-process, one-use continuation of the currently verified OIDC requester; never accepts a self-reported actor. */
public interface AuthenticatedTaskScope {
    Runnable capture(UUID expectedRequester, Runnable task);

    /** Recheck the captured scope's expiry before beginning a new action, not before settling an incurred cost. */
    void requireActive();
}
