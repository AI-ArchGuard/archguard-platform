package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

/**
 * 验证恢复调度的最小控制流：Schema 不可用时跳过，数据库失败不逃出调度，过期候选查询有数量和时间边界。存储替身返回空集合，不证明真实恢复写入。
 */
class AgentRecoveryTest {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Instant> selectedCutoff = new AtomicReference<>();
    private boolean unavailable;
    private final AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
        new Class<?>[] {AgentStore.class}, (proxy, method, args) -> {
            if (!method.getName().equals("expired")) throw new AssertionError("Unexpected store write: " + method.getName());
            calls.incrementAndGet();
            selectedCutoff.set((Instant) args[0]);
            assertThat(args[1]).isEqualTo(100);
            if (unavailable) throw new IllegalStateException("synthetic database outage");
            return List.of();
        });
    private final AgentSchemaAvailability schema = new AgentSchemaAvailability(() -> {});
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
    private final AgentRecovery recovery = new AgentRecovery(store,
        new AgentTransitions(store, null, event -> { throw new AssertionError("Unexpected audit write"); }, clock,
            new AgentRequestRouting(new LiveRequestStore() {
                public boolean hasDailyAssociation(java.util.UUID project, java.util.UUID request) { return false; }
                public void insert(Link link) { throw new AssertionError("Recovery cannot write links"); }
                public java.util.Optional<Link> find(java.util.UUID project, java.util.UUID request) {
                    throw new AssertionError("Recovery does not filter routing");
                }
            })), schema, clock);

    // 恢复依赖独立 Agent Schema 就绪；不可用时不查询，查询故障不破坏调度线程。
    @Test void schemaFailureSkipsRecoveryAndDatabaseFailureDoesNotEscape() {
        recovery.recoverExpired();
        assertThat(calls.get()).isZero();
        schema.migrateOnReady();
        unavailable = true;
        assertThatCode(recovery::recoverExpired).doesNotThrowAnyException();
        assertThat(calls.get()).isOne();
    }

    // 固定时钟明确选择截止点，同时断言查询上限，避免无界扫描。
    @Test void selectionIsBoundedAndUsesHardTimeoutWithGrace() {
        schema.migrateOnReady();
        Instant cutoff = Instant.parse("2026-09-30T23:59:00Z");
        recovery.recoverExpired();
        assertThat(calls.get()).isOne();
        assertThat(selectedCutoff.get()).isEqualTo(cutoff);
    }
}
