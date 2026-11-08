package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.*;
import io.github.aiarchguard.platform.agent.*;
import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 原测试仍通过公开合成入口独立运行，此处让同一套隐私、回调和费用断言经过窄能力入口。 */
class DailyLiveExecutionTest extends LiveExecutionCoordinatorTest {
    @Override void execute(LiveExecutionCoordinator core) {
        executeBounded(core, new LiveExecutionBudget(nanos::get));
    }
    @Override void executeBounded(LiveExecutionCoordinator core, LongSupplier remaining) {
        core.execute(lease(accounting), remaining);
    }
    @Override void executeWithLedger(LiveExecutionCoordinator core, LiveAccountingOperations ledger) {
        core.execute(lease(ledger), new LiveExecutionBudget(nanos::get));
    }

    // 仅桥接原夹具的账本计数与故障注入，不复制任何生产执行逻辑。
    LiveExecutionLease lease(LiveAccountingOperations ledger) {
        return new LiveExecutionLease() {
            public UUID projectId() { return project; }
            public UUID requestId() { return request; }
            public LiveAccountingOperations.Admission reserve(LongSupplier left) {
                if (ledger==accounting) return accounting.reserveRequest(request,left);
                // 此分支仅复用旧测试显式传入的无界合成账本，验证默认有界方法拒绝。
                return ledger.reserve(project,batch,template,request,left);
            }
            public LiveAccountingOperations.SendApproval checkBeforeSend(LongSupplier left) {
                if (ledger==accounting) return accounting.checkRequest(left);
                return ledger.checkBeforeSend(project,batch,template,request,left);
            }
            public LiveAccountingOperations.Outcome settle(String price, LiveTokenUsage usage) {
                return ledger.settle(project,request,price,usage);
            }
            public LiveAccountingOperations.Outcome unknown() { return ledger.unknown(project,request); }
            public void stop(String code) { accounting.stops.incrementAndGet(); }
            public void lockAndValidateSuccess(AgentSnapshot snapshot, LongSupplier left) {
                assertThat(left.getAsLong()).isPositive();
                assertThat(accounting.settled).hasValue(1);
                // 恢复任务可能已经赢得终态，保留后续 RUNNING-only CAS 的空操作语义。
                assertThat(requests.value.view().state()).isNotEqualTo("SUCCEEDED");
            }
            public void completeSuccess() { assertThat(requests.value.view().state()).isEqualTo("SUCCEEDED"); }
        };
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void dailyAndSyntheticShareOneSendAlgorithm(boolean daily) {
        provider.onSend = () -> {
            assertThat(accounting.checks).hasValue(2);
            assertThat(provider.prepares).hasValue(1);
            assertThat(accounting.settled).hasValue(0);
        };
        var core = core(validResources());
        var originalDeadline = new LiveExecutionBudget(nanos::get);
        nanos.set(Duration.ofSeconds(11).toNanos());
        if (daily) executeBounded(core, originalDeadline);
        else {
            var link=new LiveRequestStore.Link(request,project,batch,template,UUID.randomUUID(),accounting.credential,1,NOW);
            var store=new LiveRequestStore() {
                public java.util.Optional<Link> find(UUID p,UUID r) {
                    assertThat(p).isEqualTo(project); assertThat(r).isEqualTo(request); return java.util.Optional.of(link);
                }
                public boolean hasDailyAssociation(UUID p,UUID r) { return false; }
                public void insert(Link ignored) { throw new AssertionError(); }
            };
            var association=new SyntheticLiveRequestAssociation(store,accounting);
            var binding=association.find(project,request).orElseThrow();
            assertThat(binding).isEqualTo(new SyntheticLiveBinding(link));
            core.execute(association.lease(binding),originalDeadline);
        }
        assertThat(provider.timeout).isEqualTo(Duration.ofSeconds(19));
        assertThat(provider.sentBody).containsExactly(provider.preparedBody);
        assertThat(provider.sentModels).containsExactly("deepseek-flash");
        assertThat(accounting.reserves).hasValue(1);
        assertThat(accounting.settled).hasValue(1);
        assertThat(provider.sends).hasValue(1);
        assertThat(requests.value.view().state()).isEqualTo("SUCCEEDED");
    }

    @Test void duplicateReservationCannotFailOrChargeWinner() {
        accounting.owns.set(true);
        execute(core(validResources()));
        assertThat(accounting.reserves).hasValue(1);
        assertThat(accounting.unknown).hasValue(0);
        assertThat(accounting.stops).hasValue(0);
        assertThat(accounting.settled).hasValue(0);
        assertThat(provider.sends).hasValue(0);
        assertThat(audit).isEmpty();
        assertThat(requests.value.view().state()).isEqualTo("RUNNING");
    }
}
