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

/** 只捕获不可变身份、权限与到期时间，不把 Jwt、Bearer Token 或调用方上下文带到异步线程。 */
@Component
final class OidcTaskScope implements AuthenticatedTaskScope {
    private final Clock clock;

    OidcTaskScope(Clock clock) { this.clock = clock; }

    /** 捕获时校验真实 JWT 和请求者；执行时一次性消费并再次检查时效，finally 恢复线程原上下文。 */
    @Override public Runnable capture(UUID expectedRequester, Runnable task) {
        Objects.requireNonNull(expectedRequester);
        Objects.requireNonNull(task);
        var current = SecurityContextHolder.getContext().getAuthentication();
        // 后台任务使用独立认证类型，不能再递归捕获新任务来延长权限寿命。
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

    /** 内部任务身份没有凭据；禁止外部通过 setAuthenticated(true) 将其重新激活。 */
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
