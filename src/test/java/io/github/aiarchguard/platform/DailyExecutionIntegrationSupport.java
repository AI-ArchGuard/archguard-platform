package io.github.aiarchguard.platform;

import static io.github.aiarchguard.platform.agent.internal.DailyControlTestConfiguration.OWNER;
import static org.assertj.core.api.Assertions.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.identity.*;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

/** 托管 SQL 夹具从创建服务取得 QUEUED 请求，不直接更新请求状态。 */
abstract class DailyExecutionIntegrationSupport extends DailyAccountingTestSupport {
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource source;
    final AtomicBoolean active = new AtomicBoolean(true);
    final AuthenticatedTaskScope scope = new AuthenticatedTaskScope() {
        public Runnable capture(UUID expected, Runnable task) { return () -> { requireActive(); task.run(); }; }
        public void requireActive() { if (!active.get()) throw new InactiveAuthenticatedTaskException(); }
    };

    Case queued(int attempts) {
        MDC.put("traceId","d".repeat(32));
        var g = grant(facts.project(),attempts,Math.max(R, attempts * R));
        var made = creation.create(facts.project(), UUID.randomUUID().toString(), input(facts,g));
        var c = new Case(facts.project(),g,made.value().request().id());
        assertThat(made.created()).isTrue();
        assertThat(callbacks).containsExactly(c.r());
        assertThat(state(c)).isEqualTo("QUEUED");
        assertThat(usage(c).activeRequestId()).isEqualTo(c.r());
        assertThat(store.attempt(c.p(),c.r())).isEmpty();
        return c;
    }

    DailyExecutionTestBridge bridge() {
        var resources = new LiveResourceAuthorizer(projects,jobs,findings,documents,reports);
        return new DailyExecutionTestBridge(store,controls,service,resources::validateDaily);
    }
    LiveDispatchTransitions transitions(AuditRecorder recorder) {
        return bridge().transitions(requests,projects,actors,scope,traces,recorder,clock,manager,work);
    }
    String state(Case c) { return requests.find(c.p(),c.r()).orElseThrow().view().state(); }
    long audits(Case c,String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action=:a AND metadata->>'requestId'=:r")
            .param("p",c.p()).param("a",action).param("r",c.r().toString()).query(Long.class).single();
    }
    void uncharged(Case c,String state) {
        assertThat(state(c)).isEqualTo(state);
        assertThat(store.attempt(c.p(),c.r())).isEmpty();
        assertThat(store.findStop(c.p(),c.g())).isEmpty();
        assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(0,0,0,c.r()));
    }
    int pid(Connection holder) throws Exception {
        try (var q=holder.createStatement(); var rows=q.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(rows.next()).isTrue(); return rows.getInt(1);
        }
    }
    void lockRequest(Connection holder,Case c) throws Exception {
        try (var q=holder.prepareStatement("SELECT id FROM agent.requests WHERE id=? FOR UPDATE")) {
            q.setQueryTimeout(5); q.setObject(1,c.r());
            try (var rows=q.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }
    void awaitWait(int pid, Future<?> pending) throws Exception {
        var observer = new AgentLiveAccountingIntegrationTest();
        observer.jdbc=jdbc;
        observer.awaitSendCheckLockWait(pid,pending);
    }
    <V> Callable<V> asOwner(Callable<V> action) {
        return () -> {
            AgentLiveAccountingIntegrationTest.identity(OWNER);
            MDC.put("traceId","d".repeat(32));
            try { return action.call(); }
            finally { SecurityContextHolder.clearContext(); MDC.clear(); }
        };
    }
}
