package io.github.aiarchguard.platform.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.identity.InactiveAuthenticatedTaskException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 验证异步任务的一次性已验证身份：去掉浏览器 token、冻结权限，并恢复线程上下文。使用本地认证对象，不执行外部 OIDC 验签。
 */
class OidcTaskScopeTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private final MutableClock clock = new MutableClock(NOW);
    private final OidcTaskScope scope = new OidcTaskScope(clock);
    private final SpringSecurityCurrentActorProvider actors = new SpringSecurityCurrentActorProvider();

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    // 仅允许捕获已认证 OIDC 身份，缺失或其他类型不能进入任务。
    void rejectsMissingAnonymousAndNonOidcAuthentication() {
        assertThatThrownBy(() -> scope.capture(OWNER, () -> {})).isInstanceOf(IllegalStateException.class);
        authenticate(new AnonymousAuthenticationToken("test", "anonymous",
            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThatThrownBy(() -> scope.capture(OWNER, () -> {})).isInstanceOf(IllegalStateException.class);
        authenticate(UsernamePasswordAuthenticationToken.authenticated(OWNER.toString(), "not-retained", List.of()));
        assertThatThrownBy(() -> scope.capture(OWNER, () -> {})).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnauthenticatedOidcAndRequesterMismatch() {
        var jwt = jwt(OWNER.toString(), NOW.plusSeconds(60));
        jwt.setAuthenticated(false);
        authenticate(jwt);
        assertThatThrownBy(() -> scope.capture(OWNER, () -> {})).isInstanceOf(IllegalStateException.class);
        authenticate(jwt(OWNER.toString(), NOW.plusSeconds(60)));
        assertThatThrownBy(() -> scope.capture(UUID.randomUUID(), () -> {})).isInstanceOf(RuntimeException.class);
    }

    @Test
    // 认证名称必须与已验证 subject 一致，不能采用另一个自报身份。
    void rejectsAuthenticationNameThatDoesNotMatchTheVerifiedSubject() {
        var original = jwt(UUID.randomUUID().toString(), NOW.plusSeconds(60));
        authenticate(new JwtAuthenticationToken(original.getToken(), original.getAuthorities(), OWNER.toString()));
        assertThatThrownBy(() -> scope.capture(OWNER, () -> {})).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsMalformedSubjectMissingExpiryAndExpiredOidc() {
        authenticate(jwt("not-a-uuid", NOW.plusSeconds(60)));
        assertThatThrownBy(() -> scope.capture(OWNER, () -> {})).isInstanceOf(RuntimeException.class);
        authenticate(jwt(OWNER.toString(), null));
        assertThatThrownBy(() -> scope.capture(OWNER, () -> {})).isInstanceOf(IllegalStateException.class);
        authenticate(jwt(OWNER.toString(), NOW));
        assertThatThrownBy(() -> scope.capture(OWNER, () -> {})).isInstanceOf(IllegalStateException.class);
    }

    @Test
    // worker 获得全新无 token 上下文，完成后恢复原线程身份。
    void runsWithFreshTokenlessContextAndImmutableAuthoritiesThenRestoresPreviousContext() {
        var oidc = jwt(OWNER.toString(), NOW.plusSeconds(60));
        authenticate(oidc);
        var original = SecurityContextHolder.getContext();
        var task = scope.capture(OWNER, () -> {
            var current = SecurityContextHolder.getContext();
            var authentication = current.getAuthentication();
            assertThat(current).isNotSameAs(original);
            assertThat(authentication).isNotInstanceOf(JwtAuthenticationToken.class);
            assertThat(authentication.getCredentials()).isNull();
            assertThat(authentication.getPrincipal()).isEqualTo(OWNER.toString());
            assertThat(authentication.getDetails()).isNull();
            assertThat(actors.currentActor().id()).isEqualTo(OWNER);
            assertThat(actors.currentActor().permissions()).containsExactly("project:create");
            scope.requireActive();
        });
        // A browser thread's SecurityContext is not retained as the execution authority.
        oidc.setAuthenticated(false);
        task.run();
        assertThat(SecurityContextHolder.getContext()).isSameAs(original);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(oidc);
    }

    @Test
    void restoresWorkerContextOnFailureAndNeverRetainsBrowserAuthentication() {
        authenticate(jwt(OWNER.toString(), NOW.plusSeconds(60)));
        RuntimeException failure = new IllegalStateException("test failure");
        var task = scope.capture(OWNER, () -> { throw failure; });
        var worker = SecurityContextHolder.createEmptyContext();
        var workerAuthentication = UsernamePasswordAuthenticationToken.authenticated("worker", null, List.of());
        worker.setAuthentication(workerAuthentication);
        SecurityContextHolder.setContext(worker);
        assertThatThrownBy(task::run).isSameAs(failure);
        assertThat(SecurityContextHolder.getContext()).isSameAs(worker);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(workerAuthentication);
    }

    @Test
    // 排队期间过期的作用域不能进入，也不能稍后重放。
    void expiredQueuedTaskNeverEntersAndCannotBeReplayed() {
        authenticate(jwt(OWNER.toString(), NOW.plusSeconds(1)));
        AtomicInteger calls = new AtomicInteger();
        var task = scope.capture(OWNER, calls::incrementAndGet);
        var original = SecurityContextHolder.getContext();
        clock.advanceSeconds(1);
        assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
        assertThat(SecurityContextHolder.getContext()).isSameAs(original);
    }

    @Test
    void successfulTaskIsSingleUseAndDoesNotAllowAuthorityDerivation() {
        authenticate(jwt(OWNER.toString(), NOW.plusSeconds(60)));
        AtomicInteger calls = new AtomicInteger();
        var task = scope.capture(OWNER, () -> {
            calls.incrementAndGet();
            assertThatThrownBy(() -> scope.capture(OWNER, () -> {})).isInstanceOf(IllegalStateException.class);
        });
        task.run();
        assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    // 并发复制仍只有首个执行者可以进入一次性作用域。
    void concurrentReplayCannotEnterTheAlreadyRunningScope() throws Exception {
        authenticate(jwt(OWNER.toString(), NOW.plusSeconds(60)));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var task = scope.capture(OWNER, () -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted test"); }
        });
        Thread worker = Thread.ofPlatform().start(() -> {
            try { task.run(); }
            catch (Throwable assertion) { failure.set(assertion); }
            finally { SecurityContextHolder.clearContext(); }
        });
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
        } finally { release.countDown(); worker.join(5_000); }
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    // 执行中过期后身份可用于历史结算，不能再授权新动作。
    void expiredInFlightIdentityRemainsReadableForSettlementButCannotAuthorizeNewActions() {
        authenticate(jwt(OWNER.toString(), NOW.plusSeconds(1)));
        scope.capture(OWNER, () -> {
            scope.requireActive();
            clock.advanceSeconds(1);
            assertThatThrownBy(scope::requireActive).isExactlyInstanceOf(InactiveAuthenticatedTaskException.class)
                .hasMessage("An active authenticated OIDC task is required").hasNoCause();
            // Identity is evidence for cost settlement, not permission to begin another action.
            assertThat(actors.currentActor().id()).isEqualTo(OWNER);
        }).run();
    }

    @Test
    void requireActiveRejectsUnscopedOidcAndOtherAuthentication() {
        assertThatThrownBy(scope::requireActive).isInstanceOf(IllegalStateException.class);
        authenticate(jwt(OWNER.toString(), NOW.plusSeconds(60)));
        assertThatThrownBy(scope::requireActive).isInstanceOf(IllegalStateException.class);
    }

    @Test
    // 线程复用时下一任务不能继承前次权限。
    void workerDoesNotLeakAuthorityToTheNextTask() throws Exception {
        authenticate(jwt(OWNER.toString(), NOW.plusSeconds(60)));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var task = scope.capture(OWNER, () -> assertThat(actors.currentActor().id()).isEqualTo(OWNER));
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                task.run();
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                assertThatThrownBy(actors::currentActor).isInstanceOf(IllegalStateException.class);
            } catch (Throwable assertion) { failure.set(assertion); }
            finally { SecurityContextHolder.clearContext(); }
        });
        worker.join(5_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
    }

    private static JwtAuthenticationToken jwt(String subject, Instant expiry) {
        Jwt token = new Jwt("test-only-oidc-value", NOW.minusSeconds(1), expiry,
            Map.of("alg", "RS256"), Map.of("sub", subject, "scope", "project:create"));
        return new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("project:create")));
    }

    private static void authenticate(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
