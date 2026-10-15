package io.github.aiarchguard.platform.identity.security;

import io.github.aiarchguard.platform.identity.AuthenticatedTaskScope;
import io.github.aiarchguard.platform.identity.InactiveAuthenticatedTaskException;
import io.github.aiarchguard.platform.identity.InvalidCurrentActorException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Captures only immutable identity evidence, never a Jwt, bearer token or caller SecurityContext. */
@Component
final class OidcTaskScope implements AuthenticatedTaskScope {
    private final Clock clock;

    OidcTaskScope(Clock clock) { this.clock = clock; }

    @Override public Runnable capture(UUID expectedRequester, Runnable task) {
        Objects.requireNonNull(expectedRequester);
        Objects.requireNonNull(task);
        var current = SecurityContextHolder.getContext().getAuthentication();
        // A captured task cannot recursively mint new continuations or extend its authority.
        if (!(current instanceof JwtAuthenticationToken oidc) || !oidc.isAuthenticated()) throw unavailable();
        UUID requester;
        try {
            requester = UUID.fromString(oidc.getToken().getSubject());
            if (!requester.equals(UUID.fromString(oidc.getName()))) throw new InvalidCurrentActorException();
        }
        catch (RuntimeException invalid) { throw new InvalidCurrentActorException(); }
        if (!expectedRequester.equals(requester)) throw new InvalidCurrentActorException();
        Instant expiresAt = oidc.getToken().getExpiresAt();
        requireFuture(expiresAt);
        List<String> authorities = oidc.getAuthorities().stream().map(a -> a.getAuthority()).distinct().sorted().toList();
        AtomicBoolean used = new AtomicBoolean();
        return () -> {
            if (!used.compareAndSet(false, true)) throw unavailable();
            requireFuture(expiresAt);
            var previous = SecurityContextHolder.getContext();
            var isolated = SecurityContextHolder.createEmptyContext();
            isolated.setAuthentication(new TaskAuthentication(requester, authorities, expiresAt));
            SecurityContextHolder.setContext(isolated);
            try { task.run(); }
            finally { SecurityContextHolder.setContext(previous); }
        };
    }

    @Override public void requireActive() {
        var current = SecurityContextHolder.getContext().getAuthentication();
        if (!(current instanceof TaskAuthentication task) || !task.isAuthenticated()) throw unavailable();
        requireFuture(task.expiresAt);
    }

    private void requireFuture(Instant expiresAt) {
        if (expiresAt == null || !clock.instant().isBefore(expiresAt)) throw unavailable();
    }

    private static InactiveAuthenticatedTaskException unavailable() {
        return new InactiveAuthenticatedTaskException();
    }

    private static final class TaskAuthentication extends AbstractAuthenticationToken {
        private final String requester;
        private final Instant expiresAt;

        private TaskAuthentication(UUID requester, List<String> authorities, Instant expiresAt) {
            super(authorities.stream().map(SimpleGrantedAuthority::new).toList());
            this.requester = requester.toString();
            this.expiresAt = expiresAt;
            super.setAuthenticated(true);
        }

        @Override public Object getCredentials() { return null; }
        @Override public Object getPrincipal() { return requester; }
        @Override public void setAuthenticated(boolean authenticated) {
            if (authenticated) throw unavailable();
            super.setAuthenticated(false);
        }
    }
}
