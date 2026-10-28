package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用可回滚内存事务验证结果写入在动作后、审计后和提交前都复核期限。关注成功结果与审计的原子性，不证明真实数据库锁等待可取消。
 */
class LiveResultTransitionsTest {
    LiveExecutionCoordinatorTest fixture;
    AtomicBoolean expired;
    AtomicInteger writes;
    @BeforeEach void prepare() {
        fixture = new LiveExecutionCoordinatorTest(); fixture.setup();
        expired = new AtomicBoolean(); writes = new AtomicInteger();
    }
    AgentRequestView.AgentResult result() {
        return new AgentRequestView.AgentResult(null, List.of(), List.of(), List.of(), List.of(), "NONE");
    }
    Runnable guard() { return () -> { if (expired.get()) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT"); }; }
    // 包装存储和审计注入精确过期时点，观察写过之后能否被同一事务回滚。
    LiveResultTransitions writer(Runnable afterWrite, Runnable afterAudit) {
        return writer(afterWrite, afterAudit, false);
    }
    LiveResultTransitions writer(Runnable afterWrite, Runnable afterAudit, boolean bounded) {
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(), new Class<?>[]{AgentStore.class},
            (proxy, method, args) -> {
                var value = method.invoke(fixture.requests, args);
                if (method.getName().equals("finish")) { writes.incrementAndGet(); afterWrite.run(); }
                return value;
            });
        var manager = new LiveExecutionCoordinatorTest.RollbackResultTransactions(fixture.requests);
        var clock = Clock.fixed(LiveExecutionCoordinatorTest.NOW, ZoneOffset.UTC);
        return bounded ? new LiveResultTransitions(store, event -> afterAudit.run(), clock, manager,
            LiveExecutionCoordinatorTest.memoryDatabaseWork(manager))
            : new LiveResultTransitions(store, event -> afterAudit.run(), clock, manager);
    }
    void timeout(LiveResultTransitions writer) {
        assertThatThrownBy(() -> writer.finishChecked(fixture.original, result(), null, guard()))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(fixture.requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(fixture.requests.value.view().result()).isNull();
    }
    // 已经过期时不能先写再补偿。
    @Test void expiryBeforeTransactionWriteNeverTouchesRequest() {
        expired.set(true); timeout(writer(() -> {}, () -> {})); assertThat(writes).hasValue(0);
    }
    // 即使存储动作已发生，随后过期也必须回滚成功。
    @Test void expiryAfterStoreWriteRollsBackSuccess() {
        timeout(writer(() -> expired.set(true), () -> {})); assertThat(writes).hasValue(1);
    }
    @Test void expiryAfterAuditRollsBackSuccess() {
        timeout(writer(() -> {}, () -> expired.set(true))); assertThat(writes).hasValue(1);
    }
    // 较早提交回调使预算失效时，最终提交检查仍须阻止提交。
    @Test void expiryInAnEarlierCommitHookStillRollsBackSuccess() {
        timeout(writer(() -> {}, () -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public int getOrder() { return Integer.MIN_VALUE; }
            @Override public void beforeCommit(boolean readOnly) { expired.set(true); }
        })));
        assertThat(writes).hasValue(1);
    }

    // 旧构造器不提供有界保证，禁止回退到无界成功或失败写入。
    @Test void legacyWriterCannotFallBackToUnboundedSuccessOrFailurePersistence() {
        var legacy = writer(() -> {}, () -> {});
        assertThatThrownBy(() -> legacy.finishBounded(fixture.original, result(), null, () -> 5_000_000_000L))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThatThrownBy(() -> legacy.failBounded(fixture.original,
            new AgentRequestView.AgentFailure("MODEL_TIMEOUT", "Synthetic timeout"), null))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(writes).hasValue(0); assertThat(fixture.requests.value).isSameAs(fixture.original);
    }

    @Test void boundedSuccessCannotSkipItsOriginalDeadline() {
        var bounded = writer(() -> {}, () -> {}, true);
        expired.set(true);
        boundedTimeout(bounded); assertThat(writes).hasValue(0);
    }

    @Test void boundedExpiryAfterStoreWriteStillRollsBackSuccess() {
        boundedTimeout(writer(() -> expired.set(true), () -> {}, true)); assertThat(writes).hasValue(1);
    }

    // 有界路径同样保证审计后过期不会留下成功结果。
    @Test void boundedExpiryAfterAuditStillRollsBackSuccess() {
        boundedTimeout(writer(() -> {}, () -> expired.set(true), true)); assertThat(writes).hasValue(1);
    }

    @Test void boundedExpiryInAnEarlierCommitHookStillRollsBackSuccess() {
        boundedTimeout(writer(() -> {}, () -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public int getOrder() { return Integer.MIN_VALUE; }
            @Override public void beforeCommit(boolean readOnly) { expired.set(true); }
        }), true));
        assertThat(writes).hasValue(1);
    }

    @Test void invalidOrThrowingBudgetCannotReachTheBoundedResultStore() {
        var bounded = writer(() -> {}, () -> {}, true);
        assertThatThrownBy(() -> bounded.finishBounded(fixture.original, result(), null, null))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThatThrownBy(() -> bounded.finishBounded(fixture.original, result(), null, () -> 30_000_000_001L))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThatThrownBy(() -> bounded.finishBounded(fixture.original, result(), null, () -> {
            throw new IllegalStateException("synthetic private clock", new IllegalArgumentException("synthetic private cause"));
        })).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(writes).hasValue(0);
    }

    private void boundedTimeout(LiveResultTransitions bounded) {
        assertThatThrownBy(() -> bounded.finishBounded(fixture.original, result(), null, () -> expired.get() ? 0L : 5_000_000_000L))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(fixture.requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(fixture.requests.value.view().result()).isNull();
    }
}
