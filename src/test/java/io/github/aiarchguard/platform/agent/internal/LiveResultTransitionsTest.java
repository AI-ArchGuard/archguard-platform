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
    /** 模拟存储与活动槽同一提交边界；费用预先独立结算，不在回滚快照中。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"success","lose","otherSlot","lockFailure","lockDeadline","casDeadline",
        "auditFailure","completionFailure","beforeCommit"})
    void dailyResultHooksMustShareTheCasTransaction(String phase) {
        var events=new java.util.ArrayList<String>();
        var slot=new java.util.concurrent.atomic.AtomicReference<>(fixture.request);
        var other=java.util.UUID.randomUUID();
        if (phase.equals("otherSlot")) slot.set(other);
        var beforeSlot=slot.get(); var before=fixture.requests.value; var persistedAudit=new AtomicInteger();
        var marker=new TransactionSynchronization() {
            public void beforeCommit(boolean readOnly) {
                if (phase.equals("beforeCommit")) {
                    events.add("beforeCommit");
                    assertThat(slot.get()).isNull();
                    throw new AgentUnavailableException();
                }
            }
            public void afterCompletion(int status) {
                if (status==STATUS_ROLLED_BACK) {
                    fixture.requests.value=before; slot.set(beforeSlot); persistedAudit.set(0);
                }
            }
        };
        Runnable sameTx=()->assertThat(TransactionSynchronizationManager.getSynchronizations()).contains(marker);
        var lease=new LiveExecutionLease() {
            public java.util.UUID projectId() { return fixture.project; }
            public java.util.UUID requestId() { return fixture.request; }
            public LiveAccountingOperations.Admission reserve(java.util.function.LongSupplier left) { throw new AssertionError(); }
            public LiveAccountingOperations.SendApproval checkBeforeSend(java.util.function.LongSupplier left) { throw new AssertionError(); }
            public LiveAccountingOperations.Outcome settle(String price,io.github.aiarchguard.platform.agent.LiveTokenUsage usage) { throw new AssertionError(); }
            public LiveAccountingOperations.Outcome unknown() { throw new AssertionError(); }
            public void stop(String code) { throw new AssertionError(); }
            public void lockAndValidateSuccess(AgentSnapshot snapshot,java.util.function.LongSupplier left) {
                events.add("lock");
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
                TransactionSynchronizationManager.registerSynchronization(marker);
                assertThat(snapshot).isSameAs(before); assertThat(left.getAsLong()).isPositive();
                if (phase.equals("otherSlot") || phase.equals("lockFailure")) throw new AgentUnavailableException();
                if (phase.equals("lockDeadline")) expired.set(true);
            }
            public void completeSuccess() {
                sameTx.run(); events.add("release");
                assertThat(fixture.requests.value.view().state()).isEqualTo("SUCCEEDED");
                assertThat(persistedAudit).hasValue(1);
                assertThat(slot.compareAndSet(fixture.request,null)).isTrue();
                if (phase.equals("completionFailure")) throw new AgentUnavailableException();
            }
        };
        var store=(AgentStore)Proxy.newProxyInstance(AgentStore.class.getClassLoader(),new Class<?>[]{AgentStore.class},(proxy,method,args)->{
            if (!method.getName().equals("finish")) throw new AssertionError(method.getName());
            sameTx.run(); events.add("cas");
            if (phase.equals("lose")) return false;
            var result=method.invoke(fixture.requests,args);
            if (phase.equals("casDeadline")) expired.set(true);
            return result;
        });
        var manager=new LiveExecutionCoordinatorTest.MemoryTransactions();
        var writer=new LiveResultTransitions(store,event->{
            sameTx.run(); events.add("audit"); persistedAudit.incrementAndGet();
            if (phase.equals("auditFailure")) throw new AgentUnavailableException();
        },Clock.fixed(LiveExecutionCoordinatorTest.NOW,ZoneOffset.UTC),manager,LiveExecutionCoordinatorTest.memoryDatabaseWork(manager));
        Runnable finish=()->writer.finishBounded(before,result(),null,()->expired.get()?0:1_000_000_000L,lease);
        if (phase.equals("success") || phase.equals("lose")) finish.run();
        else assertThatThrownBy(finish::run).isInstanceOf(RuntimeException.class);
        var expected=switch (phase) {
            case "lockFailure","otherSlot","lockDeadline" -> java.util.List.of("lock");
            case "lose","casDeadline" -> java.util.List.of("lock","cas");
            case "auditFailure" -> java.util.List.of("lock","cas","audit");
            case "beforeCommit" -> java.util.List.of("lock","cas","audit","release","beforeCommit");
            default -> java.util.List.of("lock","cas","audit","release");
        };
        assertThat(events).containsExactlyElementsOf(expected);
        boolean success=phase.equals("success");
        assertThat(fixture.requests.value.view().state()).isEqualTo(success?"SUCCEEDED":"RUNNING");
        assertThat(slot.get()).isEqualTo(success?null:beforeSlot);
        assertThat(persistedAudit).hasValue(success?1:0);
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
