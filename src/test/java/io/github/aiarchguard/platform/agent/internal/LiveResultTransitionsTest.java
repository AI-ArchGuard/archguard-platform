package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentRequestView;
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
    LiveResultTransitions writer(Runnable afterWrite, Runnable afterAudit) {
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(), new Class<?>[]{AgentStore.class},
            (proxy, method, args) -> {
                var value = method.invoke(fixture.requests, args);
                if (method.getName().equals("finish")) { writes.incrementAndGet(); afterWrite.run(); }
                return value;
            });
        return new LiveResultTransitions(store, event -> afterAudit.run(), Clock.fixed(LiveExecutionCoordinatorTest.NOW, ZoneOffset.UTC),
            new LiveExecutionCoordinatorTest.RollbackResultTransactions(fixture.requests));
    }
    void timeout(LiveResultTransitions writer) {
        assertThatThrownBy(() -> writer.finishChecked(fixture.original, result(), null, guard()))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(fixture.requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(fixture.requests.value.view().result()).isNull();
    }
    @Test void expiryBeforeTransactionWriteNeverTouchesRequest() {
        expired.set(true); timeout(writer(() -> {}, () -> {})); assertThat(writes).hasValue(0);
    }
    @Test void expiryAfterStoreWriteRollsBackSuccess() {
        timeout(writer(() -> expired.set(true), () -> {})); assertThat(writes).hasValue(1);
    }
    @Test void expiryAfterAuditRollsBackSuccess() {
        timeout(writer(() -> {}, () -> expired.set(true))); assertThat(writes).hasValue(1);
    }
    @Test void expiryInAnEarlierCommitHookStillRollsBackSuccess() {
        timeout(writer(() -> {}, () -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public int getOrder() { return Integer.MIN_VALUE; }
            @Override public void beforeCommit(boolean readOnly) { expired.set(true); }
        })));
        assertThat(writes).hasValue(1);
    }
}
