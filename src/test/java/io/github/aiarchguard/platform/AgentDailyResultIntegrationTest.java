package io.github.aiarchguard.platform;

import static io.github.aiarchguard.platform.agent.internal.DailyControlTestConfiguration.*;
import static org.assertj.core.api.Assertions.*;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.*;

/** 真实 PostgreSQL 事务中的成功状态、审计和活动槽；独立结算必须跨结果回滚保留。 */
class AgentDailyResultIntegrationTest extends DailyExecutionIntegrationSupport {
    static final String SUCCESS="agent.live.result_succeeded";
    Money deploymentBefore;
    Case running(int attempts) {
        deploymentBefore=deployment();
        var c=queued(attempts);
        assertThat(transitions(audit).start(c.p(),c.r(),deadline(5))).isNotNull();
        assertThat(state(c)).isEqualTo("RUNNING");
        admitted(c);
        return c;
    }
    Case settled(int attempts) {
        var c=running(attempts);
        assertThat(service.settle(c.p(),c.r(),PRICE,TRUSTED)).isEqualTo(new LiveAccountingOperations.Outcome("SETTLED",273L));
        settledMoney(c);
        assertThat(usage(c).activeRequestId()).isEqualTo(c.r());
        return c;
    }
    void settledMoney(Case c) {
        var a=store.attempt(c.p(),c.r()).orElseThrow();
        assertThat(a.state()).isEqualTo("SETTLED"); assertThat(a.actual()).isEqualTo(273L);
        assertThat(usage(c).attempts()).isOne();
        assertThat(usage(c).reservedMicrousd()).isZero(); assertThat(usage(c).spentMicrousd()).isEqualTo(273);
        assertThat(project(c)).isEqualTo(new Money(0,273));
        assertThat(deployment()).isEqualTo(deploymentBefore.plus(0,273));
    }
    void finish(Case c,AuditRecorder recorder,LongSupplier left) {
        var snapshot=requests.find(c.p(),c.r()).orElseThrow();
        var result=new AgentRequestView.AgentResult(null,List.of(),List.of(),List.of(),List.of(),"INSUFFICIENT");
        var usage=new AgentRequestView.AgentUsage(1000,100,1,1L,R,273L,"resp-fixture","deepseek-flash");
        bridge().finish(new LiveResultTransitions(requests,recorder,clock,manager,work),snapshot,result,usage,left);
    }
    void retained(Case c) {
        assertThat(state(c)).isEqualTo("RUNNING"); assertThat(audits(c,SUCCESS)).isZero();
        assertThat(usage(c).activeRequestId()).isEqualTo(c.r()); settledMoney(c);
    }
    @Test void dailySuccessAndSlotReleaseCommitTogether() {
        var c=settled(2);
        assertThatThrownBy(()->creation.create(c.p(),UUID.randomUUID().toString(),input(facts,c.g())))
            .isInstanceOf(AgentDailyActionInProgressException.class);
        finish(c,audit,deadline(5));
        assertThat(state(c)).isEqualTo("SUCCEEDED"); assertThat(audits(c,SUCCESS)).isOne();
        assertThat(usage(c).activeRequestId()).isNull(); settledMoney(c);
        assertThat(creation.create(c.p(),UUID.randomUUID().toString(),input(facts,c.g())).created()).isTrue();
    }

    @Test void lastAllowedSettledAttemptCanStillPublishItsOwnResult() {
        var c=settled(1);
        assertThat(store.findGrant(c.p(),c.g()).orElseThrow().maxAttempts()).isEqualTo(usage(c).attempts());
        finish(c,audit,deadline(5));
        assertThat(state(c)).isEqualTo("SUCCEEDED"); assertThat(audits(c,SUCCESS)).isOne();
        assertThat(usage(c).activeRequestId()).isNull(); settledMoney(c);
        assertThatThrownBy(()->creation.create(c.p(),UUID.randomUUID().toString(),input(facts,c.g())))
            .isInstanceOf(AgentDailyStateInvalidException.class);
    }

    @ParameterizedTest @ValueSource(strings={"audit","beforeCommit","auditDeadline"})
    void failedResultTransactionRetainsSettledActiveRequest(String phase) {
        var c=settled(2); var afterCas=new AtomicBoolean(); var beforeCommit=new AtomicBoolean();
        var expired=new AtomicBoolean(); var budget=deadline(8);
        AuditRecorder recorder=event->{
            audit.record(event);
            assertThat(state(c)).isEqualTo("SUCCEEDED"); afterCas.set(true);
            assertThat(usage(c).activeRequestId()).isEqualTo(c.r());
            switch(phase) {
                case "audit" -> throw new AgentUnavailableException();
                case "auditDeadline" -> expired.set(true);
                case "beforeCommit" -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    public void beforeCommit(boolean readOnly) {
                        assertThat(usage(c).activeRequestId()).isNull(); beforeCommit.set(true); throw new AgentUnavailableException();
                    }
                });
                default -> throw new AssertionError();
            }
        };
        assertThatThrownBy(()->finish(c,recorder,()->expired.get()?0:budget.getAsLong())).isInstanceOf(RuntimeException.class);
        assertThat(afterCas).isTrue(); assertThat(beforeCommit.get()).isEqualTo(phase.equals("beforeCommit")); retained(c);
    }

    @ParameterizedTest @ValueSource(strings={"stopped","grant","risk","expiry","rotation","binding","settings","role","resource"})
    void independentSettlementDoesNotOverrideCurrentAuthority(String phase) {
        var c=settled(2);
        switch(phase) {
            case "stopped" -> service.stop(c.p(),c.r(),"INTERNAL_ERROR");
            case "grant" -> controls.revokeGrant(c.p(),c.g());
            case "risk" -> controls.revokeRisk(c.p(),store.findRequest(c.p(),c.r()).orElseThrow().riskAcknowledgementId());
            case "expiry" -> TIME.set(clock.instant().plusSeconds(1200));
            case "rotation" -> METADATA.set(new CredentialStatus(true,UUID.randomUUID(),clock.instant()));
            case "binding" -> ENV.setProperty("archguard.agent.daily.runtime-revision","b".repeat(64));
            case "settings" -> work.transaction(deadline(5),false,()->{ store.updateSettings(c.p(),true,c.g()); return true; });
            case "role" -> assertThat(jdbc.sql("DELETE FROM project.project_members WHERE project_id=:p AND actor_id=:a")
                .param("p",c.p()).param("a",OWNER).update()).isOne();
            case "resource" -> assertThat(jdbc.sql("DELETE FROM finding.finding_evidences WHERE evidence_id=:id AND finding_id=:f")
                .param("id",facts.evidence()).param("f",facts.finding()).update()).isOne();
            default -> throw new AssertionError();
        }
        assertThatThrownBy(()->finish(c,audit,deadline(5))).isInstanceOf(RuntimeException.class);
        retained(c);
    }

    @ParameterizedTest @ValueSource(strings={"pending","unknown"})
    void unsettledAttemptNeverPublishes(String phase) {
        var c=running(2);
        if (phase.equals("unknown")) service.unknown(c.p(),c.r());
        assertThatThrownBy(()->finish(c,audit,deadline(5))).isInstanceOf(RuntimeException.class);
        assertThat(state(c)).isEqualTo("RUNNING");
        assertThat(audits(c,SUCCESS)).isZero(); assertThat(usage(c).activeRequestId()).isEqualTo(c.r());
        assertThat(store.attempt(c.p(),c.r()).orElseThrow().state()).isEqualTo(phase.equals("unknown")?"UNKNOWN":null);
    }

    @Test void staleResultCannotReleaseSlotAfterRecoveryWins() {
        var c=settled(2); var stale=requests.find(c.p(),c.r()).orElseThrow();
        assertThat(requests.failRunning(c.r(),new AgentRequestView.AgentFailure("INTERNAL_ERROR","Recovered"),clock.instant())).isTrue();
        var writer=new LiveResultTransitions(requests,audit,clock,manager,work);
        bridge().finish(writer,stale,new AgentRequestView.AgentResult(null,List.of(),List.of(),List.of(),List.of(),"INSUFFICIENT"),
            null,deadline(5));
        assertThat(state(c)).isEqualTo("FAILED"); assertThat(audits(c,SUCCESS)).isZero();
        assertThat(usage(c).activeRequestId()).isEqualTo(c.r()); settledMoney(c);
    }

    @Test void staleLeaseCannotClearAnotherRealRequestsActiveSlot() {
        var c=settled(2);
        // 显式故障夹具：模拟旧槽已交回，随后由真实创建服务取得第二个精确关联。
        assertThat(jdbc.sql("UPDATE agent.daily_grant_usage SET active_request_id=NULL WHERE grant_id=:g AND active_request_id=:r")
            .param("g",c.g()).param("r",c.r()).update()).isOne();
        var next=creation.create(c.p(),UUID.randomUUID().toString(),input(facts,c.g())).value().request();
        assertThat(next.id()).isNotEqualTo(c.r());
        assertThat(usage(c).activeRequestId()).isEqualTo(next.id());
        assertThatThrownBy(()->finish(c,audit,deadline(5))).isInstanceOf(RuntimeException.class);
        assertThat(state(c)).isEqualTo("RUNNING"); assertThat(audits(c,SUCCESS)).isZero();
        assertThat(usage(c).activeRequestId()).isEqualTo(next.id()); settledMoney(c);
    }

    @org.junit.jupiter.params.ParameterizedTest @ValueSource(ints={0,1,2,3})
    void resultAcquiresOnlyEarlierRowsWhileBlockedAtEachPrefixPosition(int held) throws Exception {
        var c=settled(2);
        var keys=List.of(c.p(),c.g(),DEPLOYMENT,c.p(),c.r());
        String budget="SELECT 1 FROM agent.live_budget_usage WHERE utc_day=DATE '"+originalDay+"' AND scope_id=:id";
        var rows=List.of("SELECT 1 FROM agent.daily_project_settings WHERE project_id=:id",
            "SELECT 1 FROM agent.daily_grant_usage WHERE grant_id=:id",budget,budget,"SELECT 1 FROM agent.requests WHERE id=:id");
        try (var pool=Executors.newSingleThreadExecutor(); var holder=source.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int pid=pid(holder);
                try (var q=holder.prepareStatement(rows.get(held).replace(":id","?")+" FOR UPDATE")) {
                    q.setQueryTimeout(5); q.setObject(1,keys.get(held));
                    try (var row=q.executeQuery()) { assertThat(row.next()).isTrue(); }
                }
                var pending=pool.submit(asOwner(()->{ finish(c,audit,deadline(20)); return null; }));
                awaitWait(pid,pending);
                for (int i=0;i<held;i++) assertLocked(rows.get(i),keys.get(i));
                for (int i=held+1;i<keys.size();i++) {
                    assertThat(jdbc.sql(rows.get(i)+" FOR UPDATE NOWAIT").param("id",keys.get(i)).query(Integer.class).single()).isOne();
                }
                holder.rollback(); pending.get(8,TimeUnit.SECONDS);
                assertThat(state(c)).isEqualTo("SUCCEEDED"); assertThat(usage(c).activeRequestId()).isNull(); settledMoney(c);
            } finally { holder.rollback(); }
        }
    }

    @Test void resultMustHoldSettingsGrantAndOriginalDailyBudgetsBeforeWaitingForRequest() throws Exception {
        var c=settled(2);
        try (var pool=Executors.newSingleThreadExecutor(); var holder=source.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int pid=pid(holder); lockRequest(holder,c);
                var pending=pool.submit(asOwner(()->{ finish(c,audit,deadline(4)); return null; }));
                awaitWait(pid,pending);
                // 同一阻塞请求存在时，其前缀的每个实际行锁都已被结果事务占用。
                assertLocked("SELECT 1 FROM agent.daily_project_settings WHERE project_id=:id",c.p());
                assertLocked("SELECT 1 FROM agent.daily_grant_usage WHERE grant_id=:id",c.g());
                String budget="SELECT 1 FROM agent.live_budget_usage WHERE utc_day=DATE '"+originalDay+"' AND scope_id=:id";
                assertLocked(budget,DEPLOYMENT); assertLocked(budget,c.p());
                assertThatThrownBy(()->pending.get(7,TimeUnit.SECONDS)).hasCauseInstanceOf(LiveResponsePort.CallFailure.class)
                    .satisfies(error->assertThat(error.getCause()).hasMessage("MODEL_TIMEOUT"));
                retained(c);
            } finally { holder.rollback(); }
        }
    }

    void assertLocked(String sql,UUID id) {
        assertThatThrownBy(()->jdbc.sql(sql+" FOR UPDATE NOWAIT").param("id",id).query(Integer.class).single())
            .hasRootCauseInstanceOf(java.sql.SQLException.class)
            .satisfies(error->assertThat(((java.sql.SQLException)org.springframework.core.NestedExceptionUtils.getMostSpecificCause(error))
                .getSQLState()).isEqualTo("55P03"));
    }

    @ParameterizedTest @ValueSource(strings={"create","revoke"})
    void newCreateOrRevocationSerializesWithResultCommit(String phase) throws Exception {
        var c=settled(2); var atAudit=new CountDownLatch(1); var release=new CountDownLatch(1); var pid=new AtomicInteger();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var publishing=pool.submit(asOwner(()->{
                finish(c,event->{
                    audit.record(event); pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single()); atAudit.countDown();
                    try { assertThat(release.await(12,TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                },deadline(20)); return null;
            }));
            try {
                assertThat(atAudit.await(5,TimeUnit.SECONDS)).isTrue();
                var competing=pool.submit(asOwner(()->{
                    if (phase.equals("create")) return creation.create(c.p(),UUID.randomUUID().toString(),input(facts,c.g())).value().request().id();
                    controls.revokeGrant(c.p(),c.g()); return null;
                }));
                awaitWait(pid.get(),competing);
                retained(c);
                release.countDown(); publishing.get(5,TimeUnit.SECONDS);
                var next=competing.get(5,TimeUnit.SECONDS);
                assertThat(state(c)).isEqualTo("SUCCEEDED"); assertThat(audits(c,SUCCESS)).isOne();
                assertThat(usage(c).activeRequestId()).isEqualTo(next); settledMoney(c);
                if (phase.equals("create")) assertThat(next).isNotNull().isNotEqualTo(c.r());
                else assertThat(store.findGrant(c.p(),c.g()).orElseThrow().revocation()).isNotNull();
            } finally { release.countDown(); }
        }
    }
}
