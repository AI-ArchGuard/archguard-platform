package io.github.aiarchguard.platform;

import static io.github.aiarchguard.platform.agent.internal.DailyControlTestConfiguration.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** 两种模式均在有限执行期限内预留，并访问同一部署、同一 UTC 日的 PostgreSQL 账本。 */
class AgentDailyCrossModeBudgetIntegrationTest extends DailyAccountingTestSupport {
    @Autowired LiveAccountingOperations live;
    @Autowired LiveRequestStore links;
    @Autowired AgentEnablementOperations enablements;

    record Synthetic(UUID project,UUID batch,UUID template,UUID request,long reservation) {}
    Synthetic synthetic(UUID project,boolean context) throws Exception {
        var fixture=new AgentLiveAccountingIntegrationTest();
        fixture.project=project; fixture.now=clock.instant(); fixture.mapper=mapper; fixture.requests=requests;
        fixture.jdbc=jdbc; fixture.enablements=enablements; fixture.accounting=live; fixture.ledger=legacyMoney; fixture.links=links;
        AgentLiveAccountingIntegrationTest.INVENTORY_REVIEWED_AT.set(clock.instant());
        fixture.template=fixture.request(null,false);
        if(context) {
            var first=new AgentContextAccountingIntegrationTest().firstContextRequest(fixture);
            return new Synthetic(project,first.batch(),first.template(),first.request(),R);
        }
        var body=mapper.valueToTree(new AgentPersonalEnablementIntegrationTest().input());
        ((ObjectNode)body).put("expiresAt",clock.instant().plusSeconds(86400).toString())
            .put("priceCatalogExpiresAt",clock.instant().plusSeconds(86400).toString());
        for(var row:body.path("sources")) ((ObjectNode)row).put("checkedAt",clock.instant().toString());
        fixture.acknowledgement=enablements.approve(project,mapper.treeToValue(body,PersonalEnablementInput.class)).id();
        enablements.update(project,enablements.settings(project).revision(),new AgentSettingsUpdate(true,fixture.acknowledgement));
        var batch=fixture.batch(2,8400);
        return new Synthetic(project,batch.id(),fixture.template,fixture.request(batch.id(),true),4200);
    }
    LiveAccountingOperations.Admission liveReserve(Synthetic s) {
        return live.reserve(s.project(),s.batch(),s.template(),s.request(),deadline(5));
    }
    @ParameterizedTest @ValueSource(strings={"live4200","liveContext","daily"})
    void crossModeAndNewGrantSeeOldReservations(String first) throws Exception {
        var c=ready();
        var s=synthetic(c.p(),!first.equals("live4200"));
        long held=first.equals("daily")?R:s.reservation();
        long spent=PROJECT_LIMIT-R;
        seed("PROJECT",c.p(),spent);
        var d=deployment();
        if(first.equals("daily")) {
            admitted(c);
            assertThat(service.unknown(c.p(),c.r())).isEqualTo(new LiveAccountingOperations.Outcome("UNKNOWN",null));
            assertThatThrownBy(()->liveReserve(s)).isInstanceOf(AgentQuotaExceededException.class);
            assertThat(legacyMoney.lockBatch(s.batch()).attempts()).isZero();
            assertThat(legacyMoney.attempt(s.project(),s.request())).isEmpty();
            var fresh=ready(facts,grant(c.p(),3,1000000));
            service=freshService(PROJECT_LIMIT,DEPLOYMENT_LIMIT);
            assertThatThrownBy(()->reserve(fresh)).isInstanceOf(AgentQuotaExceededException.class);
            assertThat(usage(fresh)).isEqualTo(new DailyAgentStore.Usage(0,0,0,fresh.r()));
            assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,R,0,c.r()));
            assertThat(count("daily_outcomes",c)).isOne(); assertThat(stopCode(c)).isNotBlank();
        } else {
            assertThat(liveReserve(s).reservedMicrousd()).isEqualTo(held);
            assertThat(live.unknown(s.project(),s.request())).isEqualTo(new LiveAccountingOperations.Outcome("UNKNOWN",null));
            service=freshService(PROJECT_LIMIT,DEPLOYMENT_LIMIT);
            assertThatThrownBy(()->reserve(c)).isInstanceOf(AgentQuotaExceededException.class);
            assertThat(count("daily_attempts",c)).isZero(); assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(0,0,0,c.r()));
            assertThat(legacyMoney.attempt(s.project(),s.request()).orElseThrow().outcome()).isEqualTo("UNKNOWN");
        }
        assertThat(project(c)).isEqualTo(new Money(held,spent)); assertThat(deployment()).isEqualTo(d.plus(held,0));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void concurrentModesCompeteForLastProjectOrDeploymentReservation(boolean differentProjects) throws Exception {
        var c=ready();
        var s=synthetic(differentProjects?facts().project():c.p(),true);
        if(differentProjects) seed("DEPLOYMENT",DEPLOYMENT,DEPLOYMENT_LIMIT-R);
        else seed("PROJECT",c.p(),PROJECT_LIMIT-R);
        var d=deployment(); var p=project(c); var sp=money("PROJECT",s.project(),originalDay);
        var winners=new AgentLiveAccountingIntegrationTest().parallel(
            ()->wins(()->reserve(c)),()->wins(()->liveReserve(s)));
        assertThat(winners).containsExactlyInAnyOrder(true,false);
        boolean dailyWon=winners.get(0);
        assertThat(count("daily_attempts",c)).isEqualTo(dailyWon?1:0);
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(dailyWon?1:0,dailyWon?R:0,0,c.r()));
        assertThat(legacyMoney.lockBatch(s.batch()).attempts()).isEqualTo(dailyWon?0:1);
        assertThat(legacyMoney.attempt(s.project(),s.request()).isPresent()).isEqualTo(!dailyWon);
        assertThat(deployment()).isEqualTo(d.plus(R,0));
        assertThat(project(c)).isEqualTo(p.plus(!differentProjects||dailyWon?R:0,0));
        if(differentProjects) assertThat(money("PROJECT",s.project(),originalDay)).isEqualTo(sp.plus(dailyWon?0:R,0));
    }
    private boolean wins(Callable<LiveAccountingOperations.Admission> operation) throws Exception {
        try { return operation.call().newAttempt(); }
        catch(AgentQuotaExceededException exhausted) { return false; }
    }
    @Test void batchAttemptCapStillAppliesWhenSharedMoneyHasHeadroom() throws Exception {
        var c=ready(); var s=synthetic(c.p(),false);
        assertThat(jdbc.sql("UPDATE agent.live_batch_usage SET attempts=2 WHERE batch_id=:b AND attempts=0")
            .param("b",s.batch()).update()).isOne();
        var d=deployment();
        assertThatThrownBy(()->liveReserve(s)).isInstanceOf(AgentQuotaExceededException.class);
        assertThat(legacyMoney.attempt(s.project(),s.request())).isEmpty();
        assertThat(legacyMoney.lockBatch(s.batch()).attempts()).isEqualTo(2);
        admitted(c);
        assertThat(project(c)).isEqualTo(new Money(R,0)); assertThat(deployment()).isEqualTo(d.plus(R,0));
        assertThat(legacyMoney.lockBatch(s.batch()).attempts()).isEqualTo(2);
    }
}
