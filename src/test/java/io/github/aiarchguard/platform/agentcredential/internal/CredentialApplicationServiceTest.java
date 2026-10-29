package io.github.aiarchguard.platform.agentcredential.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.identity.CurrentActor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** 验证管理服务的 owner、本地配置、Origin 与审计前置条件，不执行模型请求。 */
class CredentialApplicationServiceTest {
    @TempDir Path root;
    final UUID owner = UUID.randomUUID();
    final ArrayList<AuditEvent> audits = new ArrayList<>();

    MockEnvironment environment() {
        var env = new MockEnvironment(); env.setActiveProfiles("local-compose");
        return env.withProperty("archguard.agent.credentials.owner-id", owner.toString())
            .withProperty("archguard.agent.credentials.enabled", "true")
            .withProperty("archguard.agent.credentials.origin", "http://localhost:8082")
            .withProperty("archguard.agent.credentials.allow-loopback-http", "true");
    }
    CredentialApplicationService service(MockEnvironment env, UUID actor) {
        return new CredentialApplicationService(env, () -> new CurrentActor(actor, Set.of("project:maintain")),
            audits::add, () -> "a".repeat(32), Clock.systemUTC());
    }

    /** Project 维护权限不能替代配置 owner；拒绝操作留下安全授权审计。 */
    @Test void onlyConfiguredOwnerNotProjectMaintainerCanManage() {
        var env = environment();
        assertThatThrownBy(() -> service(env, UUID.randomUUID()).authorize("http://localhost:8082", false, true))
            .isInstanceOf(CredentialFailure.class).hasMessage("credential.denied");
        assertThat(audits).hasSize(1);
        service(env, owner).authorize("http://localhost:8082", false, true);
    }

    /** 开关关闭、owner 缺失或 profile 不符均不可用，保持默认失败关闭。 */
    @Test void defaultDisabledMissingOwnerAndWrongProfileFailClosed() {
        var env = environment(); env.setProperty("archguard.agent.credentials.enabled", "false");
        assertThatThrownBy(() -> service(env, owner).authorize(null, false, false)).hasMessage("credential.unavailable");
        env.setProperty("archguard.agent.credentials.enabled", "true"); env.setActiveProfiles("oidc");
        assertThatThrownBy(() -> service(env, owner).authorize(null, false, false)).hasMessage("credential.unavailable");
        assertThatThrownBy(() -> service(new MockEnvironment(), owner).status()).hasMessage("credential.unavailable");
    }

    /** 写操作缺失/错误 Origin 和未经明确允许的 HTTP 均拒绝；HTTPS 还要求实际安全传输。 */
    @Test void hostileMissingOriginAndNonOptedInHttpAreDenied() {
        var env = environment(); var service = service(env, owner);
        for (String origin : new String[]{null, "https://attacker.invalid", "http://localhost:8082/"}) {
            assertThatThrownBy(() -> service.authorize(origin, false, true)).hasMessage("credential.denied");
        }
        env.setProperty("archguard.agent.credentials.allow-loopback-http", "false");
        assertThatThrownBy(() -> service.authorize("http://localhost:8082", false, true)).hasMessage("credential.denied");
        assertThatThrownBy(() -> service.authorize("http://localhost:8082", true, true)).hasMessage("credential.denied");
        env.setProperty("archguard.agent.credentials.origin", "https://localhost:8082");
        assertThatThrownBy(() -> service.authorize("https://localhost:8082", false, true)).hasMessage("credential.denied");
        service.authorize("https://localhost:8082", true, true);
    }

    /** 公网或携带路径、用户信息、查询的配置 Origin 不能启用个人管理。 */
    @Test void publicAndMalformedConfiguredOriginsCannotEnableManagement() {
        var env = environment();
        for (String origin : new String[]{"https://public.example.com", "http://localhost/path", "http://user@localhost", "http://localhost?x=1"}) {
            env.setProperty("archguard.agent.credentials.origin", origin);
            assertThatThrownBy(() -> service(env, owner).authorize(origin, true, true)).hasMessage("credential.unavailable");
        }
    }

    /** 意图审计抛错必须先于文件变更失败，且错误不泄露底层详情。 */
    @Test void failedIntentAuditPreventsAnyCredentialMutation() throws Exception {
        var env = environment();
        Path directory = Files.createDirectory(root.resolve("encrypted")); PrivateCredentialFiles.restrict(directory, true);
        Path master = Files.write(root.resolve("master"), new byte[32]); PrivateCredentialFiles.restrict(master, false);
        env.setProperty("archguard.agent.credentials.directory", directory.toString());
        env.setProperty("archguard.agent.credentials.master-file", master.toString());
        env.setProperty("archguard.agent.credentials.deployment-id", UUID.randomUUID().toString());
        var service = new CredentialApplicationService(env, () -> new CurrentActor(owner, Set.of()),
            event -> { throw new IllegalStateException("untrusted provider-like error"); }, () -> "a".repeat(32), Clock.systemUTC());
        assertThatThrownBy(() -> service.write("synthetic-test-only-credential")).hasMessage("credential.unavailable").hasNoCause();
        assertThat(Files.exists(directory.resolve("deepseek.credential"))).isFalse();
    }
}
