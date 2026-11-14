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

    // V18 禁止双关联插入；只在关联端口制造损坏，验证调度不选任一模式。
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"daily","legacy","both","missing"})
    void routesSelectedRequestExactlyOnce(String mode) {
        var fixture=new LiveExecutionCoordinatorTest(); fixture.setup();
        var sample=fixture.original;
        var prefix=sample.view().bindings();
        var bindings=new io.github.aiarchguard.platform.agent.AgentRequestView.VersionBindings(
            prefix.scanJobId(),prefix.reportSha256(),prefix.prHeadRevisionId(),prefix.findingIds(),prefix.documentVersions(),
            prefix.promptVersion(),io.github.aiarchguard.platform.agent.LiveCostPolicy.CONTEXT_MODEL_PROFILE,
            prefix.modelProtocolVersion(),prefix.modelId(),prefix.outputSchemaVersion(),
            io.github.aiarchguard.platform.agent.LiveCostPolicy.CONTEXT_VERSION,prefix.inputDigest());
        var view=sample.view();
        var selected=new AgentSnapshot(new io.github.aiarchguard.platform.agent.AgentRequestView(view.id(),view.projectId(),
            view.requesterId(),view.traceId(),view.purpose(),"RUNNING",bindings,null,null,null,
            clock.instant().minusSeconds(150),clock.instant().minusSeconds(120)),sample.input(),sample.candidates());
        var legacyCalls=new AtomicInteger(); var dailyCalls=new AtomicInteger(); var selections=new AtomicInteger();
        var cutoff=clock.instant().minusSeconds(60);
        var facts=new LiveRequestStore() {
            public boolean hasDailyAssociation(java.util.UUID p,java.util.UUID r) { return mode.equals("daily")||mode.equals("both"); }
            public java.util.Optional<Link> find(java.util.UUID p,java.util.UUID r) {
                if(!mode.equals("legacy")&&!mode.equals("both")) return java.util.Optional.empty();
                return java.util.Optional.of(new Link(r,p,fixture.batch,fixture.template,fixture.owner,fixture.owner,1,selected.view().createdAt()));
            }
            public void insert(Link link) { throw new AssertionError("恢复不能写入关联"); }
        };
        var candidates=(AgentStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AgentStore.class},(proxy,m,args)->{
            assertThat(m.getName()).isEqualTo("expired"); assertThat(args).containsExactly(cutoff,100); selections.incrementAndGet(); return List.of(selected);
        });
        var legacy=new AgentTransitions(candidates,null,null,clock,new AgentRequestRouting(facts)){
            @Override public void recoverExpired(AgentSnapshot s,Instant at) {
                assertThat(s).isEqualTo(selected); assertThat(at).isEqualTo(cutoff); legacyCalls.incrementAndGet();
            }
        };
        var daily=new DailyAgentRecovery(){
            @Override public void recoverExpired(AgentSnapshot s,Instant at) {
                assertThat(s).isEqualTo(selected); assertThat(at).isEqualTo(cutoff); dailyCalls.incrementAndGet();
            }
        };
        schema.migrateOnReady();
        new AgentRecovery(candidates,legacy,schema,clock,facts,daily).recoverExpired();
        assertThat(selections).hasValue(1);
        assertThat(legacyCalls).hasValue(mode.equals("legacy")?1:0);
        assertThat(dailyCalls).hasValue(mode.equals("daily")?1:0);
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
