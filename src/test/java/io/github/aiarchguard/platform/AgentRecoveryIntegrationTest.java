package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.internal.AgentSnapshot;
import io.github.aiarchguard.platform.agent.internal.AgentStore;
import io.github.aiarchguard.platform.agent.internal.AgentBudgetStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 验证真实存储上的遗留请求恢复：过期请求失败、不重试模型、不释放未知费用，并使用独立调度器。通过重建恢复对象模拟重启，不启动新的生产进程。
 */
@SpringBootTest(properties = {"archguard.agent.recovery-delay=3600000"})
class AgentRecoveryIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired AgentStore store;
    @Autowired AgentBudgetStore budgets;
    @Autowired JdbcClient jdbc;
    @Autowired ConfigurableApplicationContext context;
    @Autowired PlatformTransactionManager transactions;
    @Autowired io.github.aiarchguard.platform.agent.internal.AgentTransitions transitions;

    // 重建恢复组件后处理过期 QUEUED/RUNNING，原预留及尝试事实不能被退款或补发。
    @Test void restartFailsExpiredRequestsWithoutRetryOrReleasingUnknownCost() {
        var fixture = AgentExplanationIntegrationTest.fixture(jdbc);
        Instant now = Instant.now();
        var queued = insert(fixture, now.minusSeconds(120));
        var running = insert(fixture, now.minusSeconds(120));
        var healthy = insert(fixture, now);
        var day = LocalDate.now(ZoneOffset.UTC);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(store.claim(running.view().id(), now.minusSeconds(90))).isTrue();
            assertThat(budgets.reserve(fixture.project(), day, 4000)).isTrue();
            store.setReservation(running.view().id(), day, 4000);
            assertThat(store.claim(healthy.view().id(), now)).isTrue();
        });
        restart();
        var failedQueue = store.find(fixture.project(), queued.view().id()).orElseThrow().view();
        var failedRun = store.find(fixture.project(), running.view().id()).orElseThrow().view();
        assertThat(failedQueue.state()).isEqualTo("FAILED");
        assertThat(failedQueue.failure().code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(failedRun.state()).isEqualTo("FAILED");
        assertThat(failedRun.failure().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(failedRun.result()).isNull();
        assertThat(store.find(fixture.project(), healthy.view().id()).orElseThrow().view().state()).isEqualTo("RUNNING");
        assertThat(jdbc.sql("SELECT reserved_microusd FROM agent.budget_usage WHERE scope='PROJECT' AND scope_id=:id AND utc_day=:day")
            .param("id", fixture.project()).param("day", day).query(Long.class).single()).isEqualTo(4000);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:id AND action='agent.request.failed'")
            .param("id", fixture.project()).query(Integer.class).single()).isEqualTo(2);
        restart();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:id AND action='agent.request.failed'")
            .param("id", fixture.project()).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT version FROM finding.findings WHERE id=:id")
            .param("id", fixture.finding()).query(Long.class).single()).isZero();
    }

    // 这里只重建恢复相关对象并运行恢复动作，模拟持久化状态跨组件重启保留。
    private void restart() {
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(ArchGuardPlatformApplication.class),
            new String[0], context, Duration.ZERO));
    }

    // 恢复使用独立调度资源，避免阻塞 Scanner 调度。
    @Test void recoveryHasItsOwnSchedulerInsteadOfUsingScannerSchedulingThreads() {
        var deterministic = context.getBean("taskScheduler", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
        var recovery = context.getBean("agentRecoveryScheduler", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
        assertThat(recovery.getScheduledThreadPoolExecutor()).isNotSameAs(deterministic.getScheduledThreadPoolExecutor());
    }

    // 选择候选后若刚被领取，更新时仍复核当前期限，不能终结新鲜执行者。
    @Test void recoveryRechecksExpiryWhenASelectedQueueRowWasJustClaimed() {
        var fixture = AgentExplanationIntegrationTest.fixture(jdbc);
        Instant now = Instant.now();
        var queued = insert(fixture, now.minusSeconds(120));
        assertThat(store.expired(now.minusSeconds(60), 100).stream().map(s -> s.view().id()))
            .contains(queued.view().id());
        assertThat(store.claim(queued.view().id(), now)).isTrue();
        transitions.recoverExpired(queued, now.minusSeconds(60));
        assertThat(store.find(fixture.project(), queued.view().id()).orElseThrow().view().state()).isEqualTo("RUNNING");
    }

    private AgentSnapshot insert(AgentExplanationIntegrationTest.Fixture fixture, Instant at) {
        var bindings = new AgentRequestView.VersionBindings(fixture.job(), "a".repeat(64), null,
            List.of(fixture.finding()), List.of(), "finding-explanation-0.1.0", "disabled-or-test-fake-0.1.0",
            "responses-v1-restricted", "deterministic-fake-or-disabled", "0.1.0", "synthetic-0.1.0", "b".repeat(64));
        var view = new AgentRequestView(UUID.randomUUID(), fixture.project(),
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "c".repeat(32),
            "FINDING_EXPLANATION", "QUEUED", bindings, null, null, null, at, at);
        var input = new AgentModelPort.ModelInput("FINDING_EXPLANATION", "0.1.0", "finding-explanation-0.1.0",
            "selected", "rule", "0.1.0", "high", "Synthetic", "module", List.of(), List.of(), null);
        var snapshot = new AgentSnapshot(view, input, List.of());
        assertThat(store.insert(snapshot, UUID.randomUUID().toString())).isTrue();
        return snapshot;
    }
}
