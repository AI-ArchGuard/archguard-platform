package io.github.aiarchguard.platform;

import static io.github.aiarchguard.platform.agent.internal.DailyControlTestConfiguration.*;
import static org.assertj.core.api.Assertions.*;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import java.sql.Connection;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

/** 本组测试验证记账服务。任务 6 验证响应接收、校验以及执行器的调用顺序。 */
class AgentDailyAccountingIntegrationTest extends DailyAccountingTestSupport {
    @Autowired DataSource source;

    @Test void reserveIsAtomicBoundedAndCountedOnce() throws Exception {
        var c = ready(); var before = deployment(); long audits = audits(c);
        BOUNDED_ONLY.set(true);
        var pair = new AgentLiveAccountingIntegrationTest().parallel(() -> reserve(c), () -> reserve(c));
        assertThat(pair).extracting(LiveAccountingOperations.Admission::newAttempt).containsExactlyInAnyOrder(true,false);
        assertThat(pair.get(0).attemptId()).isEqualTo(pair.get(1).attemptId());
        assertThat(pair).extracting(LiveAccountingOperations.Admission::reservedMicrousd).containsOnly(R);
        assertThat(count("daily_attempts",c)).isOne();
        assertThat(jdbc.sql("SELECT attempt_id FROM agent.daily_attempts WHERE request_id=:r AND utc_day=:day AND deployment_id=:d")
            .param("r",c.r()).param("day",originalDay).param("d",DEPLOYMENT).query(UUID.class).single()).isEqualTo(pair.get(0).attemptId());
        assertThat(audits(c)).isEqualTo(audits+1);
        var remaining = deadline(5);
        var approval = service.checkBeforeSend(c.p(),c.r(),remaining);
        assertThat(ORIGINAL_BUDGET.get()).isSameAs(remaining);
        assertThat(approval.credentialVersion()).isEqualTo(VERSION);
        assertThat(approval.allowedResponseModels()).containsExactly("deepseek-flash");
        pending(c,before);
    }
    private long audits(Case c) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action LIKE 'agent.daily.%'")
            .param("p",c.p()).query(Long.class).single();
    }
    private void pending(Case c,Money before) {
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,R,0,c.r()));
        assertThat(project(c)).isEqualTo(new Money(R,0));
        assertThat(deployment()).isEqualTo(before.plus(R,0));
    }
    @ParameterizedTest @ValueSource(strings={"credential","grant","risk","settings","expired","elapsed"})
    void sendRecheckRejectsChangedAuthorizationWithoutChangingReservation(String phase) {
        var c=ready(); var before=deployment(); admitted(c);
        assertThat(service.checkBeforeSend(c.p(),c.r(),deadline(5)).credentialVersion()).isEqualTo(VERSION);
        switch (phase) {
            case "credential" -> METADATA.set(new CredentialStatus(true,UUID.randomUUID(),clock.instant()));
            case "grant" -> controls.revokeGrant(c.p(),c.g());
            case "risk" -> controls.revokeRisk(c.p(),store.findRequest(c.p(),c.r()).orElseThrow().riskAcknowledgementId());
            case "settings" -> controls.updateSettings(c.p(),controls.settings(c.p()).revision(),
                new DailyAgentOperations.DailySettingsInput("0.1.0",false,null));
            case "expired" -> TIME.set(clock.instant().plusSeconds(1201));
            case "elapsed" -> { /* 此分支传入已耗尽的单调时钟执行预算。 */ }
            default -> throw new AssertionError(phase);
        }
        if(phase.equals("elapsed")) {
            assertThatThrownBy(()->service.checkBeforeSend(c.p(),c.r(),()->0)).isInstanceOf(LiveResponsePort.CallFailure.class)
                .hasMessage("MODEL_TIMEOUT");
        } else {
            assertThatThrownBy(()->service.checkBeforeSend(c.p(),c.r(),deadline(5))).isInstanceOf(AgentDailyStateInvalidException.class);
        }
        pending(c,before); assertThat(count("daily_outcomes",c)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"settle","unknown","stop"})
    void cleanupCommitsIndependentlyOfFailingCallerTransaction(String operation) {
        var c=ready(); var before=deployment(); admitted(c);
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(()->work.transaction(deadline(5),false,()->{
            switch(operation) {
                case "settle" -> service.settle(c.p(),c.r(),PRICE,TRUSTED);
                case "unknown" -> service.unknown(c.p(),c.r());
                case "stop" -> service.stop(c.p(),c.r(),"INTERNAL_ERROR");
                default -> throw new AssertionError(operation);
            }
            failBeforeCommit(reached); return true;
        })).isInstanceOf(AgentUnavailableException.class);
        assertThat(reached).isTrue();
        if(operation.equals("settle")) {
            assertThat(count("daily_outcomes",c)).isOne();
            assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,273,c.r()));
            assertThat(project(c)).isEqualTo(new Money(0,273)); assertThat(deployment()).isEqualTo(before.plus(0,273));
        } else {
            pending(c,before);
            assertThat(count("daily_outcomes",c)).isEqualTo(operation.equals("unknown")?1:0);
            assertThat(stopCode(c)).isNotBlank();
        }
    }
    @Test void auditFailureRollsBackEveryReservationWrite() {
        var c=ready(); var before=deployment();
        FAIL_AUDIT.set(true);
        assertThatThrownBy(()->reserve(c)).isInstanceOf(AgentUnavailableException.class);
        assertThat(FAIL_AUDIT.get()).as("reservation must have reached the failing audit").isFalse();
        assertThat(count("daily_attempts",c)).isZero();
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(0,0,0,c.r()));
        assertThat(project(c)).isEqualTo(new Money(0,0)); assertThat(deployment()).isEqualTo(before);
        admitted(c); pending(c,before);
    }
    @ParameterizedTest @ValueSource(strings={"attempts","grant","project","deployment","overflow","revoked","credential","settings"})
    void insufficientOrStaleAuthorizationMakesNoNewAttempt(String reason) {
        var c=ready();
        switch(reason) {
            case "attempts","grant" -> {
                assertThat(jdbc.sql("UPDATE agent.daily_grant_usage SET attempts=:n,spent_microusd=:s WHERE grant_id=:g")
                    .param("n",reason.equals("attempts")?3:1).param("s",reason.equals("grant")?1000000-R+1:0).param("g",c.g()).update()).isOne();
            }
            case "project" -> seed("PROJECT",c.p(),PROJECT_LIMIT-R+1);
            case "deployment" -> seed("DEPLOYMENT",DEPLOYMENT,DEPLOYMENT_LIMIT-R+1);
            case "overflow" -> seed("PROJECT",c.p(),Long.MAX_VALUE-R+1);
            case "revoked" -> controls.revokeGrant(c.p(),c.g());
            case "credential" -> METADATA.set(new CredentialStatus(true,UUID.randomUUID(),clock.instant()));
            case "settings" -> controls.updateSettings(c.p(),controls.settings(c.p()).revision(),new DailyAgentOperations.DailySettingsInput("0.1.0",false,null));
            default -> throw new AssertionError(reason);
        }
        var d=deployment(); var p=project(c); var grant=usage(c);
        assertThatThrownBy(()->reserve(c)).isInstanceOfAny(AgentQuotaExceededException.class,AgentDailyStateInvalidException.class);
        assertThat(count("daily_attempts",c)).isZero(); assertThat(usage(c)).isEqualTo(grant);
        assertThat(project(c)).isEqualTo(p); assertThat(deployment()).isEqualTo(d);
    }
    @ParameterizedTest @ValueSource(strings={"settings","grant","deployment","project","send","expires","midnight"})
    void admissionWaitsOnRealRowAndHonorsOriginalDeadlineOrClock(String phase) throws Exception {
        if (phase.equals("midnight")) TIME.set(originalDay.atTime(23,59,59).toInstant(ZoneOffset.UTC));
        var c=ready(); var d=deployment(); boolean send=phase.equals("send");
        if (send) admitted(c);
        String table,field; UUID id;
        switch(phase) {
            case "settings","send" -> { table="daily_project_settings"; field="project_id"; id=c.p(); }
            case "grant","expires" -> { table="daily_grant_usage"; field="grant_id"; id=c.g(); }
            default -> {
                id=phase.equals("project")?c.p():DEPLOYMENT;
                seed(phase.equals("project")?"PROJECT":"DEPLOYMENT",id,0);
                table="live_budget_usage"; field="scope_id";
            }
        }
        boolean clockChange=phase.equals("expires") || phase.equals("midnight");
        final Case target=c;
        var pool=Executors.newSingleThreadExecutor();
        try (Connection holder=source.getConnection()) {
            holder.setAutoCommit(false);
            try {
                var observe=new AgentLiveAccountingIntegrationTest(); observe.jdbc=jdbc;
                int pid;
                try(var q=holder.createStatement(); var rows=q.executeQuery("SELECT pg_backend_pid()")) {
                    assertThat(rows.next()).isTrue(); pid=rows.getInt(1);
                }
                String dayFilter=table.equals("live_budget_usage")?" AND utc_day=DATE '"+originalDay+"'":"";
                try(var q=holder.prepareStatement("SELECT 1 FROM agent."+table+" WHERE "+field+"=?"+dayFilter+" FOR UPDATE")) {
                    q.setQueryTimeout(5); q.setObject(1,id);
                    try(var rows=q.executeQuery()) { assertThat(rows.next()).isTrue(); }
                }
                var waiting=pool.submit(()->{
                    AgentLiveAccountingIntegrationTest.identity(OWNER);
                    try {
                        var budget=deadline(clockChange?8:2);
                        if(send) service.checkBeforeSend(target.p(),target.r(),budget);
                        else service.reserve(target.p(),target.r(),budget);
                        return null;
                    } catch(RuntimeException failure) { return failure; }
                    finally { SecurityContextHolder.clearContext(); org.slf4j.MDC.clear(); }
                });
                observe.awaitSendCheckLockWait(pid,waiting);
                if(clockChange) {
                    TIME.set(clock.instant().plusSeconds(phase.equals("expires")?1201:2));
                    holder.rollback();
                    assertThat(waiting.get(5,TimeUnit.SECONDS)).isInstanceOf(AgentDailyStateInvalidException.class);
                } else {
                    assertThat(waiting.get(5,TimeUnit.SECONDS)).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT");
                }
            } finally { holder.rollback(); }
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(8,TimeUnit.SECONDS)).isTrue(); }
        if(send) pending(c,d);
        else {
            assertThat(count("daily_attempts",c)).isZero(); assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(0,0,0,c.r()));
            assertThat(project(c)).isEqualTo(new Money(0,0)); assertThat(deployment()).isEqualTo(d);
        }
    }
    @Test void settlementUsesOriginalUtcDayAndNeverRestoresAttempts() throws Exception {
        TIME.set(originalDay.atTime(23,59,59).toInstant(ZoneOffset.UTC));
        var c=ready(); var d=deployment(); admitted(c);
        var next=originalDay.plusDays(1); TIME.set(clock.instant().plusSeconds(2));
        assertThatThrownBy(()->service.checkBeforeSend(c.p(),c.r(),deadline(5))).isInstanceOf(AgentDailyStateInvalidException.class);
        var settled=new LiveAccountingOperations.Outcome("SETTLED",273L);
        var pair=new AgentLiveAccountingIntegrationTest().parallel(
            ()->service.settle(c.p(),c.r(),PRICE,TRUSTED),()->service.settle(c.p(),c.r(),PRICE,TRUSTED));
        assertThat(pair).containsOnly(settled);
        assertThat(count("daily_outcomes",c)).isOne(); assertThat(project(c)).isEqualTo(new Money(0,273));
        assertThat(deployment()).isEqualTo(d.plus(0,273)); assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,273,c.r()));
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_budget_usage WHERE utc_day=:day AND scope_id IN (:p,:d)")
            .param("day",next).param("p",c.p()).param("d",DEPLOYMENT).query(Long.class).single()).isZero();
        work.transaction(deadline(5),false,()->{ resultPrefix(c); succeed(c); return true; });
        var second=ready(facts,c.g()); admitted(second);
        assertThat(usage(second)).isEqualTo(new DailyAgentStore.Usage(2,R,273,second.r()));
        assertThat(money("PROJECT",c.p(),next)).isEqualTo(new Money(R,0));
        assertThat(project(c)).isEqualTo(new Money(0,273)); assertThat(deployment()).isEqualTo(d.plus(0,273));
    }
    @ParameterizedTest @ValueSource(strings={"zero","unknown","missing","invalid","price"})
    void onlyTrustedZeroCanPersistZero(String scenario) {
        var c=ready(); var d=deployment(); admitted(c);
        var zero=new LiveTokenUsage(0,0,0,0,0);
        var value=switch(scenario) {
            case "unknown" -> service.unknown(c.p(),c.r());
            case "missing" -> service.settle(c.p(),c.r(),PRICE,null);
            case "invalid" -> service.settle(c.p(),c.r(),PRICE,new LiveTokenUsage(1,0,2,1,0));
            case "price" -> service.settle(c.p(),c.r(),"wrong-price",TRUSTED);
            default -> service.settle(c.p(),c.r(),PRICE,zero);
        };
        boolean trusted=scenario.equals("zero");
        assertThat(value).isEqualTo(new LiveAccountingOperations.Outcome(trusted?"SETTLED":"UNKNOWN",trusted?0L:null));
        assertThat(service.settle(c.p(),c.r(),PRICE,TRUSTED)).isEqualTo(value);
        assertThat(count("daily_outcomes",c)).isOne();
        var row=jdbc.sql("SELECT state,actual_microusd,usage IS NULL AS absent FROM agent.daily_outcomes WHERE request_id=:r")
            .param("r",c.r()).query().singleRow();
        assertThat(row.get("state")).isEqualTo(value.state()); assertThat(row.get("actual_microusd")).isEqualTo(value.actualMicrousd());
        assertThat(row.get("absent")).isEqualTo(!trusted);
        if(trusted) {
            assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,0,c.r()));
            assertThat(deployment()).isEqualTo(d); assertThat(project(c)).isEqualTo(new Money(0,0));
        } else {
            pending(c,d); assertThat(stopCode(c)).isNotBlank(); assertThat(service.unknown(c.p(),c.r())).isEqualTo(value);
        }
    }
    @ParameterizedTest @ValueSource(strings={"output","citation","8001","revoked","settings","rotation","resultRollback","stopFailure","costFailure"})
    void trustedUsageSurvivesOutputFailureRevocationAndCleanupFailure(String phase) {
        var c=ready(); var d=deployment(); admitted(c);
        // 任务 5 模拟可信调用方提交用量，再单独报告输出校验或结果提交失败。
        String code=switch(phase) {
            case "citation" -> "CITATION_INVALID";
            case "8001" -> "INPUT_TOKEN_LIMIT";
            case "revoked","settings","rotation" -> "AUTHORIZATION_REVOKED";
            case "resultRollback","stopFailure" -> "INTERNAL_ERROR";
            default -> "OUTPUT_INVALID";
        };
        if(phase.equals("revoked")) controls.revokeGrant(c.p(),c.g());
        if(phase.equals("settings")) controls.updateSettings(c.p(),controls.settings(c.p()).revision(),new DailyAgentOperations.DailySettingsInput("0.1.0",false,null));
        if(phase.equals("rotation")) METADATA.set(new CredentialStatus(true,UUID.randomUUID(),clock.instant()));
        if(phase.equals("costFailure")) {
            var failed=new java.util.concurrent.atomic.AtomicBoolean();
            service=new DailyAccountingService(controls,store,requests,work,event->{
                audit.record(event);
                if (!failed.get()) failBeforeCommit(failed);
            },traces,clock,PROJECT_LIMIT,DEPLOYMENT_LIMIT);
            assertThatThrownBy(()->service.settle(c.p(),c.r(),PRICE,TRUSTED)).isInstanceOf(AgentUnavailableException.class);
            assertThat(failed).isTrue(); assertThat(count("daily_outcomes",c)).isZero(); pending(c,d);
            return;
        }
        boolean over=phase.equals("8001"); long actual=over?2521:273;
        var cost=service.settle(c.p(),c.r(),PRICE,over?new LiveTokenUsage(8001,100,0,8101,0):TRUSTED);
        assertThat(cost).isEqualTo(new LiveAccountingOperations.Outcome("SETTLED",actual));
        if(phase.equals("resultRollback")) {
            var failed=new java.util.concurrent.atomic.AtomicBoolean();
            assertThatThrownBy(()->work.transaction(deadline(5),false,()->{
                resultPrefix(c); succeed(c); failBeforeCommit(failed); return true;
            })).isInstanceOf(AgentUnavailableException.class);
            assertThat(failed).isTrue();
            assertThat(requests.find(c.p(),c.r()).orElseThrow().view().state()).isEqualTo("RUNNING");
        }
        if(phase.equals("stopFailure")) {
            FAIL_AUDIT.set(true);
            assertThatThrownBy(()->service.stop(c.p(),c.r(),code)).isInstanceOf(AgentUnavailableException.class);
            assertThat(FAIL_AUDIT.get()).isFalse(); assertThat(store.findStop(c.p(),c.g())).isEmpty();
        } else {
            if(!over) service.stop(c.p(),c.r(),code);
            assertThat(stopCode(c)).isEqualTo(code);
        }
        assertThat(service.settle(c.p(),c.r(),PRICE,TRUSTED)).isEqualTo(cost);
        assertThat(count("daily_outcomes",c)).isOne(); assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,actual,c.r()));
        assertThat(project(c)).isEqualTo(new Money(0,actual)); assertThat(deployment()).isEqualTo(d.plus(0,actual));
        assertThatThrownBy(()->creation.create(c.p(),UUID.randomUUID().toString(),input(facts,c.g())))
            .isInstanceOfAny(AgentDailyActionInProgressException.class,AgentDailyStateInvalidException.class);
    }
    private void failBeforeCommit(java.util.concurrent.atomic.AtomicBoolean reached) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    reached.set(true); throw new AgentUnavailableException();
                }
            });
    }
    @Test void completeSuccessOnlyParticipatesInTheExactSettledResultTransaction() {
        var c=ready(); var d=deployment(); admitted(c);
        assertThatThrownBy(()->work.transaction(deadline(5),false,()->{
            resultPrefix(c); service.completeSuccess(c.p(),c.r()); return true;
        })).isInstanceOf(RuntimeException.class);
        assertThat(usage(c).activeRequestId()).isEqualTo(c.r());
        service.settle(c.p(),c.r(),PRICE,TRUSTED);
        assertThatThrownBy(()->service.completeSuccess(c.p(),c.r())).isInstanceOf(RuntimeException.class);
        assertThat(usage(c).activeRequestId()).isEqualTo(c.r());
        work.transaction(deadline(5),false,()->{ resultPrefix(c); succeed(c); return true; });
        assertThat(requests.find(c.p(),c.r()).orElseThrow().view().state()).isEqualTo("SUCCEEDED");
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,273,null));
        assertThatThrownBy(()->work.transaction(deadline(5),false,()->{
            resultPrefix(c); service.completeSuccess(c.p(),c.r()); return true;
        })).isInstanceOf(RuntimeException.class);
        var second=ready(facts,c.g());
        assertThatThrownBy(()->work.transaction(deadline(5),false,()->{
            resultPrefix(c); service.completeSuccess(c.p(),c.r()); return true;
        })).isInstanceOf(RuntimeException.class);
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,273,second.r()));
        assertThat(project(c)).isEqualTo(new Money(0,273)); assertThat(deployment()).isEqualTo(d.plus(0,273));
    }

    @Test void lastAuthorizedAttemptMaySendAndAResultCasMissRetainsItsSlot() {
        var c=ready(facts,grant(facts.project(),1,R)); var d=deployment(); admitted(c);
        assertThat(service.checkBeforeSend(c.p(),c.r(),deadline(5)).credentialVersion()).isEqualTo(VERSION);
        assertThat(service.settle(c.p(),c.r(),PRICE,TRUSTED)).isEqualTo(new LiveAccountingOperations.Outcome("SETTLED",273L));
        // 模拟另一写入方先将请求置为终态。仅成功写入结果的调用方可以释放占槽。
        assertThat(requests.finish(c.r(),null,new AgentRequestView.AgentFailure("INTERNAL_ERROR","Synthetic"),null,clock.instant())).isTrue();
        work.transaction(deadline(5),false,()->{
            resultPrefix(c);
            boolean won=requests.finish(c.r(),new AgentRequestView.AgentResult("Synthetic",List.of(),List.of(),List.of(),List.of(),"INSUFFICIENT"),
                null,null,clock.instant());
            if(won) service.completeSuccess(c.p(),c.r());
            assertThat(won).isFalse(); return true;
        });
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,273,c.r()));
        assertThat(project(c)).isEqualTo(new Money(0,273)); assertThat(deployment()).isEqualTo(d.plus(0,273));
    }
}
