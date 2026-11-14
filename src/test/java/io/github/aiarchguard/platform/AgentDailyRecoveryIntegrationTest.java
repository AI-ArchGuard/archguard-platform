package io.github.aiarchguard.platform;

import static io.github.aiarchguard.platform.agent.internal.DailyControlTestConfiguration.*;
import static org.assertj.core.api.Assertions.*;

import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/** 恢复测试使用已提交的真实授权、请求、尝试及结算。迟到与重启均只推进本地状态。 */
@org.springframework.context.annotation.Import(AgentDailyRecoveryIntegrationTest.NoProvider.class)
class AgentDailyRecoveryIntegrationTest extends DailyExecutionIntegrationSupport {
    // 单独的数据库和上下文。真实调度入口由测试驱动，定时线程不能抢先清理本轮候选。
    static final String RECOVERY_DATABASE=createDatabase();
    @org.springframework.test.context.DynamicPropertySource
    static void liveDatabaseProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        AgentLiveDatabaseIntegrationTestSupport.liveDatabaseProperties(registry);
        registry.add("spring.datasource.url",()->RECOVERY_DATABASE);
    }
    @org.springframework.boot.test.context.TestConfiguration
    static class NoProvider {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        LiveAdapterBundle recoveryProbe() { return new Probe(); }
    }
    static class Probe implements LiveAdapterBundle {
        final AtomicInteger provider=new AtomicInteger(), credentials=new AtomicInteger();
        public LiveResponsePort responses() { provider.incrementAndGet(); throw new AssertionError("恢复不应初始化网络"); }
        public LiveExecutionCoordinator.CredentialUse credentials() { credentials.incrementAndGet(); throw new AssertionError("恢复不应获取凭据"); }
        public void close() {}
        public boolean awaitTermination(Duration timeout) { return true; }
    }
    @Autowired LiveAdapterBundle recoveryProbe;
    @org.junit.jupiter.api.BeforeEach void manualSchedule() {
        application.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)
            .getScheduledTasks().stream().filter(t->t.getTask().getRunnable() instanceof org.springframework.scheduling.support.ScheduledMethodRunnable r
                && r.getTarget() instanceof AgentRecovery).forEach(t->t.cancel(false));
    }
    @org.junit.jupiter.api.AfterEach void noProviderWasInitialized() {
        var probe=(Probe)recoveryProbe;
        assertThat(probe.provider).hasValue(0); assertThat(probe.credentials).hasValue(0);
    }
    @Test void scheduledPassRoutesDailyToLocalCleanupOnce() {
        var c=running(true); TIME.set(clock.instant().plusSeconds(61));
        var recovery=application.getBean(AgentRecovery.class);
        recovery.recoverExpired(); recovery.recoverExpired();
        assertThat(state(c)).isEqualTo("FAILED");
        assertThat(store.attempt(c.p(),c.r()).orElseThrow().state()).isEqualTo("UNKNOWN");
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,R,0,c.r()));
        assertThat(store.findStop(c.p(),c.g()).orElseThrow().requestId()).isEqualTo(c.r());
        assertThat(audits(c,"agent.request.failed")).isZero();
    }
    @Autowired ApplicationContext application;

    void recover(AgentSnapshot selected, Instant cutoff) {
        application.getBean(DailyAgentRecovery.class).recoverExpired(selected, cutoff);
    }
    AgentSnapshot snapshot(Case c) { return requests.find(c.p(),c.r()).orElseThrow(); }
    Case running(boolean attempt) {
        var c=queued(3);
        assertThat(transitions(audit).start(c.p(),c.r(),deadline(5))).isNotNull();
        if (attempt) admitted(c);
        return c;
    }
    void settle(Case c) {
        assertThat(service.settle(c.p(),c.r(),PRICE,TRUSTED))
            .isEqualTo(new LiveAccountingOperations.Outcome("SETTLED",273L));
    }
    void finish(Case c, AuditRecorder recorder) {
        var result=new AgentRequestView.AgentResult(null,List.of(),List.of(),List.of(),List.of(),"INSUFFICIENT");
        var amounts=new AgentRequestView.AgentUsage(1000,100,1,1L,R,273L,"resp-fixture","deepseek-flash");
        bridge().finish(new LiveResultTransitions(requests,recorder,clock,manager,work),snapshot(c),result,amounts,deadline(15));
    }

    @ParameterizedTest @ValueSource(strings={"queued","running","reserved","unknown","settled"})
    void recoveryNeverResendsAndKeepsSettlement(String phase) throws Exception {
        boolean charged=Set.of("reserved","unknown","settled").contains(phase);
        boolean settled=phase.equals("settled");
        var baseline=deployment();
        var c=phase.equals("queued")?queued(3):running(charged);
        if (settled) settle(c);
        if (phase.equals("unknown")) service.unknown(c.p(),c.r());
        var originalStop=store.findStop(c.p(),c.g());
        var originalAttempt=store.attempt(c.p(),c.r());
        if (phase.equals("unknown")) {
            assertThat(originalStop.orElseThrow()).isEqualTo(
                new DailyAgentOperations.DailyStop(c.r(),"OUTPUT_INVALID",clock.instant()));
            assertThat(originalAttempt.orElseThrow().state()).isEqualTo("UNKNOWN");
        }
        var selected=snapshot(c);
        // 跨日并撤销，原调用资格已失效，仍必须依据原关联与原日记账。
        controls.revokeGrant(c.p(),c.g());
        TIME.set(clock.instant().plus(Duration.ofDays(1)));
        var cutoff=clock.instant().minusSeconds(60);
        recover(selected,cutoff);
        recover(selected,cutoff);
        assertThat(state(c)).isEqualTo("FAILED");
        assertThat(snapshot(c).view().result()).isNull();
        if (phase.equals("queued")) assertThat(snapshot(c).view().failure().code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(store.findStop(c.p(),c.g()).orElseThrow().requestId()).isEqualTo(c.r());
        var expected=new Money(charged&&!settled?R:0,settled?273:0);
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(charged?1:0,expected.reserved(),expected.spent(),c.r()));
        assertThat(project(c)).isEqualTo(expected);
        assertThat(deployment()).isEqualTo(baseline.plus(expected.reserved(),expected.spent()));
        for(var id:List.of(c.p(),DEPLOYMENT)) assertThat(money(id.equals(c.p())?"PROJECT":"DEPLOYMENT",id,originalDay.plusDays(1))).isEqualTo(new Money(0,0));
        if(charged) {
            var previous=store.attempt(c.p(),c.r()).orElseThrow();
            assertThat(previous.state()).isEqualTo(settled?"SETTLED":"UNKNOWN");
            service.settle(c.p(),c.r(),PRICE,TRUSTED);
            assertThat(store.attempt(c.p(),c.r()).orElseThrow()).isEqualTo(previous);
            assertThat(project(c)).isEqualTo(expected);
        } else assertThat(store.attempt(c.p(),c.r())).isEmpty();
        assertThat(audits(c,"agent.request.failed")).as("不能进入旧恢复审计").isZero();
        assertThat(callbacks).containsExactly(c.r());
        String accountingState=charged?(settled?"SETTLED":"UNKNOWN"):"NOT_ATTEMPTED";
        assertSingleAudit(c,"agent.daily.request.failed","FAILURE",Map.of("requestId",c.r(),
            "failureCode","MODEL_TIMEOUT","accountingState",accountingState,"recovery",true));
        String stopCode=phase.equals("unknown")?"OUTPUT_INVALID":"MODEL_TIMEOUT";
        var stop=store.findStop(c.p(),c.g()).orElseThrow();
        assertThat(stop.code()).isEqualTo(stopCode);
        if (phase.equals("unknown")) {
            assertThat(stop).isEqualTo(originalStop.orElseThrow());
            assertThat(store.attempt(c.p(),c.r())).isEqualTo(originalAttempt);
        }
        assertSingleAudit(c,"agent.daily.grant_stopped","SUCCESS",Map.of("requestId",c.r(),"grantId",c.g(),"code",stopCode));
    }

    private void assertSingleAudit(Case c,String action,String result,Map<String,Object> metadata) throws Exception {
        // 夹具的项目唯一；先取该动作的全部行，错报原因或额外一条审计都必须失败。
        var matches=jdbc.sql("""
            SELECT result=:result AND metadata=CAST(:metadata AS jsonb)
            FROM audit.audit_records WHERE project_id=:project AND action=:action
            """).param("project",c.p()).param("action",action).param("result",result)
            .param("metadata",mapper.writeValueAsString(metadata)).query(Boolean.class).list();
        assertThat(matches).as("%s 的唯一审计须匹配结果及元数据",action).containsExactly(true);
    }

    @Test void staleQueuedSelectionDoesNotStopFreshClaim() {
        var c=queued(2); var selected=snapshot(c);
        TIME.set(clock.instant().plusSeconds(59));
        // 原请求在排队期限内刚被领取，旧创建时间虽已过期，当前 RUNNING 的时间仍很新。
        assertThat(transitions(audit).start(c.p(),c.r(),deadline(5))).isNotNull();
        TIME.set(clock.instant().plusSeconds(2));
        recover(selected,clock.instant().minusSeconds(60));
        uncharged(c,"RUNNING");
    }

    @Test void oldSuccessfulSnapshotCannotStopOrClearTheNextActiveSlot() {
        var c=running(true); var selected=snapshot(c);
        settle(c); finish(c,audit);
        var next=creation.create(c.p(),"new-action",input(facts,c.g())).value().request().id();
        TIME.set(clock.instant().plusSeconds(61));
        recover(selected,clock.instant().minusSeconds(60));
        assertThat(state(c)).isEqualTo("SUCCEEDED");
        assertThat(store.findStop(c.p(),c.g())).isEmpty();
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,273,next));
    }

    @Test void lateResultAfterRecoveryCannotOverwriteFailureOrReleaseSlot() {
        var c=running(true); settle(c); var selected=snapshot(c);
        TIME.set(clock.instant().plusSeconds(61)); recover(selected,clock.instant().minusSeconds(60));
        var failed=snapshot(c); var stop=store.findStop(c.p(),c.g()).orElseThrow();
        assertThat(failed.view().state()).isEqualTo("FAILED");
        assertThatThrownBy(()->finish(c,audit)).isInstanceOf(RuntimeException.class);
        recover(selected,clock.instant().minusSeconds(60));
        assertThat(snapshot(c)).isEqualTo(failed); assertThat(store.findStop(c.p(),c.g()).orElseThrow()).isEqualTo(stop);
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,273,c.r()));
        assertThat(audits(c,"agent.live.result_succeeded")).isZero();
    }

    @Test void boundedCleanupFailureCanRetryWithoutNewAttempt() throws Exception {
        var c=running(true); var selected=snapshot(c);
        TIME.set(clock.instant().plusSeconds(61)); var cutoff=clock.instant().minusSeconds(60);
        try(var worker=Executors.newSingleThreadExecutor();var holder=source.getConnection()) {
            holder.setAutoCommit(false);
            try {
                lockRequest(holder,c);
                var pending=worker.submit(()->recover(selected,cutoff));
                awaitWait(pid(holder),pending);
                // 已等待请求行时，恢复必须已持有结果事务使用的四个前置锁。
                assertLocked("agent.daily_project_settings","project_id",c.p());
                assertLocked("agent.daily_grant_usage","grant_id",c.g());
                assertLocked("agent.live_budget_usage","scope_id",DEPLOYMENT);
                assertLocked("agent.live_budget_usage","scope_id",c.p());
                assertThatThrownBy(()->pending.get(8,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThat(state(c)).isEqualTo("RUNNING");
                assertThat(store.findStop(c.p(),c.g())).isEmpty();
                assertThat(store.attempt(c.p(),c.r()).orElseThrow().state()).isNull();
                assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,R,0,c.r()));
            } finally { holder.rollback(); }
        }
        recover(selected,cutoff);
        assertThat(state(c)).isEqualTo("FAILED");
        assertThat(store.attempt(c.p(),c.r()).orElseThrow().state()).isEqualTo("UNKNOWN");
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,R,0,c.r()));
    }
    void assertLocked(String table,String key,UUID id) {
        String day=table.endsWith("budget_usage")?" AND utc_day=DATE '"+originalDay+"'":"";
        assertThatThrownBy(()->jdbc.sql("SELECT 1 FROM "+table+" WHERE "+key+"=:id"+day+" FOR UPDATE NOWAIT").param("id",id).query(Integer.class).single())
            .hasRootCauseInstanceOf(java.sql.SQLException.class)
            .satisfies(error->assertThat(((java.sql.SQLException)org.springframework.core.NestedExceptionUtils.getMostSpecificCause(error)).getSQLState()).isEqualTo("55P03"));
    }

    @Test void recoveryAndRevocationWaitForSuccessfulResultWithoutStoppingGrant() throws Exception {
        var c=running(true); settle(c); var selected=snapshot(c);
        TIME.set(clock.instant().plusSeconds(61)); var cutoff=clock.instant().minusSeconds(60);
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var pid=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(3)) {
            var success=pool.submit(asOwner(()->{
                finish(c,event->{
                    audit.record(event); pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single()); held.countDown();
                    try { assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); }
                    catch(InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new AssertionError(cancelled); }
                }); return null;
            }));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var recoveryPid=new AtomicInteger();
                var revokePid=new AtomicInteger();
                var recovery=observedRecovery(recoveryPid);
                var recovering=pool.submit(()->recovery.recoverExpired(selected,cutoff));
                // 默认 REQUIRED，与 revokeGrant 的事务共享同一连接。
                var revoking=pool.submit(asOwner(()->new TransactionTemplate(manager).execute(status->{
                    revokePid.set(backendPid());
                    return controls.revokeGrant(c.p(),c.g());
                })));
                awaitBothBlocked(pid.get(),recoveryPid,recovering,revokePid,revoking);
                release.countDown(); success.get(5,TimeUnit.SECONDS);
                recovering.get(5,TimeUnit.SECONDS); revoking.get(5,TimeUnit.SECONDS);
                assertThat(state(c)).isEqualTo("SUCCEEDED");
                assertThat(store.findStop(c.p(),c.g())).isEmpty();
                assertThat(store.findGrant(c.p(),c.g()).orElseThrow().revocation()).isNotNull();
                assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,273,null));
            } finally { release.countDown(); }
        }
    }
    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private DailyAgentRecovery observedRecovery(AtomicInteger pid) {
        var observed=new LiveDatabaseWork() {
            @Override public <T> T cleanup(Supplier<T> action) {
                // 从生产清理事务内部取 PID；期限、锁、回调和提交仍由原实现负责。
                return work.cleanup(()->{
                    pid.set(backendPid());
                    return action.get();
                });
            }
        };
        return DailyExecutionTestBridge.recovery(new DailyAccountingService(controls,store,requests,
            observed,audit,traces,clock,PROJECT_LIMIT,DEPLOYMENT_LIMIT));
    }

    private void awaitBothBlocked(int owner,AtomicInteger recoveryPid,Future<?> recovering,
            AtomicInteger revokePid,Future<?> revoking) throws InterruptedException {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        List<Integer> observed=List.of();
        while (System.nanoTime()<until && !recovering.isDone() && !revoking.isDone()) {
            int recovery=recoveryPid.get(), revoke=revokePid.get();
            if (recovery>0 && revoke>0) {
                assertThat(List.of(owner,recovery,revoke)).doesNotHaveDuplicates();
                // 队列中后一会话可能等待前一会话。路径有深度上限，同一路径不重访 PID。
                observed=jdbc.sql("""
                    WITH RECURSIVE waits(origin,pid,path) AS (
                        SELECT pid,pid,ARRAY[pid] FROM (VALUES (CAST(:recovery AS integer)),
                            (CAST(:revoke AS integer))) AS workers(pid)
                        UNION ALL
                        SELECT w.origin,b.pid,array_append(w.path,b.pid) FROM waits w
                        CROSS JOIN LATERAL unnest(pg_blocking_pids(w.pid)) AS b(pid)
                        WHERE w.pid<>:owner AND cardinality(w.path)<8 AND NOT (b.pid=ANY(w.path))
                    )
                    SELECT DISTINCT origin FROM waits WHERE pid=:owner
                    """).param("recovery",recovery).param("revoke",revoke).param("owner",owner)
                    .query(Integer.class).list();
                if (observed.size()==2) break;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertThat(observed).as("恢复与撤销必须同时等待持锁的成功事务 %s",owner)
            .containsExactlyInAnyOrder(recoveryPid.get(),revokePid.get());
        assertThat(recovering.isDone()).isFalse();
        assertThat(revoking.isDone()).isFalse();
    }
}
