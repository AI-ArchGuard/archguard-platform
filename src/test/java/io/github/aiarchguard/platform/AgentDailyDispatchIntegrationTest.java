package io.github.aiarchguard.platform;

import static io.github.aiarchguard.platform.agent.internal.DailyControlTestConfiguration.*;
import static org.assertj.core.api.Assertions.*;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 日常执行权仅来自已提交的精确 CAS。所有队列均为手工推进，不装配模型。 */
class AgentDailyDispatchIntegrationTest extends DailyExecutionIntegrationSupport {
    private static final String RUNNING = "agent.daily.request.running";

    @ParameterizedTest @ValueSource(strings={"valid","invalid","transport"})
    void persistedClaimRunsRealCoreWithOnlyAnInMemoryProvider(String reply) {
        var c=queued(1); var prepares=new AtomicInteger(); var sends=new AtomicInteger();
        var resources=new LiveResourceAuthorizer(projects,jobs,findings,documents,reports);
        LiveResponsePort port=new LiveResponsePort() {
            public byte[] prepare(AgentModelPort.ModelInput input) {
                prepares.incrementAndGet();
                return new io.github.aiarchguard.platform.agent.infrastructure.DeepSeekRequestPreparation().prepare(input);
            }
            public Reply send(byte[] body,String fake,java.time.Duration left,Set<String> models) {
                throw new AssertionError("不应选择旧计费路径");
            }
            public Reply send(byte[] body,String fake,java.time.Duration left,Set<String> models,String price) {
                sends.incrementAndGet(); assertThat(models).containsExactly("deepseek-flash");
                assertThat(price).isEqualTo(PRICE); assertThat(left).isPositive().isLessThanOrEqualTo(java.time.Duration.ofSeconds(30));
                if (reply.equals("transport")) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
                String json= """
                    {"schemaVersion":"0.1.0","purpose":"FINDING_EXPLANATION","conclusion":{"kind":"INSUFFICIENT","text":null,"citationIds":[]},
                     "claims":[],"ruleBasis":[],"suggestions":[],"limitations":["Synthetic evidence only"]}
                    """;
                return new Reply(reply.equals("valid")?json:"{}",null,TRUSTED,"resp-fixture","deepseek-flash");
            }
        };
        var dispatcher=bridge().coreDispatcher(requests,actors,scope,transitions(audit),resources::validateDaily,port,
            new LiveResultTransitions(requests,audit,clock,manager,work),new AgentOutputValidator(mapper),work);
        dispatcher.dispatch(c.p(),c.r()); dispatcher.dispatch(c.p(),c.r());
        assertThat(prepares).hasValue(1); assertThat(sends).hasValue(1); assertThat(usage(c).attempts()).isOne();
        var attempt=store.attempt(c.p(),c.r()).orElseThrow();
        assertThat(attempt.state()).isEqualTo(reply.equals("transport")?"UNKNOWN":"SETTLED");
        assertThat(attempt.actual()).isEqualTo(reply.equals("transport")?null:273L);
        assertThat(state(c)).isEqualTo(reply.equals("valid")?"SUCCEEDED":"FAILED");
        if (reply.equals("valid")) {
            assertThat(usage(c).activeRequestId()).isNull(); assertThat(store.findStop(c.p(),c.g())).isEmpty();
        } else {
            assertThat(usage(c).activeRequestId()).isEqualTo(c.r());
            assertThat(store.findStop(c.p(),c.g()).orElseThrow().code()).isEqualTo(reply.equals("transport")?"MODEL_TIMEOUT":"OUTPUT_INVALID");
        }
    }

    @Test void failedUnownedLeaseCannotStopOrFinishTheWinner() {
        var c=queued(2);
        var winner=transitions(audit).start(c.p(),c.r(),deadline(5));
        assertThat(winner).isNotNull();
        var admitted=reserve(c); assertThat(admitted.newAttempt()).isTrue();
        var prior=store.attempt(c.p(),c.r()).orElseThrow(); var priorUsage=usage(c);
        var link=store.findRequest(c.p(),c.r()).orElseThrow();
        assertThatThrownBy(()->DailyExecutionTestBridge.tryUnverifiedLease(store,controls,service,link))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(store.findStop(c.p(),c.g())).isEmpty(); assertThat(usage(c)).isEqualTo(priorUsage);
        assertThat(state(c)).isEqualTo("RUNNING"); assertThat(prior.state()).isNull();
        assertThat(store.attempt(c.p(),c.r()).orElseThrow()).isEqualTo(prior);
        assertThat(audits(c,"agent.live.result_failed")).isZero();
    }

    @Test void claimLoserCannotSendOrFailWinner() throws Exception {
        var c=queued(2);
        var winner=transitions(audit);
        var claim=winner.start(c.p(),c.r(),deadline(5));
        assertThat(claim.snapshot().view().id()).isEqualTo(c.r());
        assertThatThrownBy(claim::link).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        var loser=transitions(audit);
        assertThat(loser.start(c.p(),c.r(),deadline(5))).isNull();
        assertThatThrownBy(()->loser.fail(claim,"INTERNAL_ERROR")).isInstanceOf(AgentUnavailableException.class);
        uncharged(c,"RUNNING");
        assertThat(audits(c,RUNNING)).isOne();
        var b=store.findRequest(c.p(),c.r()).orElseThrow();
        String metadata=jdbc.sql("SELECT metadata::text FROM audit.audit_records WHERE project_id=:p AND action=:a")
            .param("p",c.p()).param("a",RUNNING).query(String.class).single();
        var expected=mapper.readTree(mapper.writeValueAsBytes(Map.of("requestId",c.r(),"grantId",c.g(),"riskAcknowledgementId",b.riskAcknowledgementId(),
            "credentialVersion",b.credentialVersion(),"settingsRevision",b.settingsRevision(),"runtimeBindingDigest",b.runtimeBindingDigest(),
            "inputDigest",claim.snapshot().view().bindings().inputDigest())));
        assertThat(mapper.readTree(metadata)).isEqualTo(expected);
        winner.fail(claim,"MODEL_UNAVAILABLE");
        assertThat(state(c)).isEqualTo("FAILED");
        assertThat(stopCode(c)).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(store.attempt(c.p(),c.r())).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"duplicate","reject","afterCommitReject","rollback","scope","age","pastAge"})
    void queueOwnership(String phase) {
        var c=queued(2); var pending=new ArrayList<Runnable>(); var sends=new AtomicInteger();
        var dispatcher=bridge().dispatcher(requests,actors,scope,transitions(audit),task->{
            if (phase.endsWith("Reject") || phase.equals("reject")) throw new RejectedExecutionException();
            pending.add(task);
        },(id,left)->{
            assertThat(id).isEqualTo(c.r()); assertThat(left.getAsLong()).isPositive();
            sends.incrementAndGet();
        });
        Runnable dispatch=()->dispatcher.dispatch(c.p(),c.r());
        switch(phase) {
            case "duplicate" -> { dispatch.run(); dispatch.run(); assertThat(pending).hasSize(2); }
            case "reject" -> assertThatThrownBy(dispatch::run).isInstanceOf(AgentUnavailableException.class);
            case "afterCommitReject" -> assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->dispatch.run()))
                .isInstanceOf(AgentUnavailableException.class);
            case "rollback" -> new TransactionTemplate(manager).executeWithoutResult(tx->{dispatch.run(); tx.setRollbackOnly();});
            case "scope","age","pastAge" -> {
                dispatch.run(); assertThat(pending).hasSize(1);
                if (phase.equals("scope")) active.set(false); else TIME.set(clock.instant().plusSeconds(phase.equals("age")?60:61));
            }
            default -> throw new AssertionError(phase);
        }
        pending.forEach(Runnable::run);
        boolean win=phase.equals("duplicate");
        assertThat(sends).hasValue(win?1:0); assertThat(audits(c,RUNNING)).isEqualTo(win?1:0);
        uncharged(c,win?"RUNNING":"QUEUED");
        if (Set.of("reject","afterCommitReject","rollback").contains(phase)) assertThat(pending).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"audit","expiry","scope"})
    void claimRolledBackAfterAudit(String phase) {
        var c=queued(2); var visits=new AtomicInteger();
        AuditRecorder changed=event->{
            audit.record(event); visits.incrementAndGet();
            switch(phase) {
                case "audit" -> throw new AgentUnavailableException();
                case "expiry" -> TIME.set(clock.instant().plusSeconds(60));
                case "scope" -> active.set(false);
                default -> throw new AssertionError();
            }
        };
        var transition=transitions(changed);
        assertThatThrownBy(()->transition.start(c.p(),c.r(),deadline(5))).isInstanceOf(RuntimeException.class);
        assertThat(visits).hasValue(1); uncharged(c,"QUEUED"); assertThat(audits(c,RUNNING)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"request","project","grant","risk","credential","revision","binding","payload","candidates","created","actor"})
    void exactAssociationIsRequired(String mismatch) {
        var c=queued(2); var b=store.findRequest(c.p(),c.r()).orElseThrow(); var other=UUID.randomUUID();
        var forged=new DailyAgentStore.RequestLink(mismatch.equals("request")?other:b.requestId(),
            mismatch.equals("project")?other:b.projectId(),mismatch.equals("grant")?other:b.grantId(),
            mismatch.equals("risk")?other:b.riskAcknowledgementId(),mismatch.equals("credential")?other:b.credentialVersion(),
            b.settingsRevision()+(mismatch.equals("revision")?1:0),mismatch.equals("binding")?"0".repeat(64):b.runtimeBindingDigest(),
            mismatch.equals("payload")?"0".repeat(64):b.payloadSha256(),mismatch.equals("candidates")?"0".repeat(64):b.candidatesSha256(),
            mismatch.equals("created")?b.createdAt().minusSeconds(1):b.createdAt());
        assertThat(work.transaction(deadline(5),false,()->store.claim(forged,mismatch.equals("actor")?other:OWNER,clock.instant()))).isFalse();
        uncharged(c,"QUEUED");
        assertThat(transitions(audit).start(c.p(),c.r(),deadline(5))).isNotNull();
    }

    @ParameterizedTest @ValueSource(strings={"modelProfileVersion","modelProtocolVersion","modelId","promptVersion","outputSchemaVersion","priceCatalogVersion","purpose","schemaVersion"})
    void immutableRequestContractRejectsMutationAndOriginalBindingCanClaim(String field) {
        var c=queued(2);
        var original=requests.find(c.p(),c.r()).orElseThrow();
        var binding=store.findRequest(c.p(),c.r()).orElseThrow();
        String column=Set.of("purpose","schemaVersion").contains(field)?"model_input":"bindings";
        // 同一语句先满足状态转换，再触发不可变检查；整条更新回滚后才可尝试真实领取。
        assertThatThrownBy(()->jdbc.sql("UPDATE agent.requests SET state='RUNNING', "+column+"=jsonb_set("+column+",ARRAY[cast(:field AS text)],to_jsonb('different'::text)) WHERE id=:r")
            .param("r",c.r()).param("field",field).update())
            .hasRootCauseInstanceOf(java.sql.SQLException.class)
            .satisfies(error->{
                var cause=(java.sql.SQLException)org.springframework.core.NestedExceptionUtils.getMostSpecificCause(error);
                assertThat(cause.getSQLState()).isEqualTo("P0001");
                assertThat(cause.getMessage()).contains("Agent request bindings are immutable");
            });
        assertThat(requests.find(c.p(),c.r()).orElseThrow()).isEqualTo(original);
        assertThat(store.findRequest(c.p(),c.r()).orElseThrow()).isEqualTo(binding);
        uncharged(c,"QUEUED"); assertThat(audits(c,RUNNING)).isZero();

        var claim=transitions(audit).start(c.p(),c.r(),deadline(5));
        assertThat(claim).isNotNull();
        assertThat(claim.snapshot().view().id()).isEqualTo(binding.requestId());
        uncharged(c,"RUNNING"); assertThat(audits(c,RUNNING)).isOne();
    }

    @Test void competingClaimWaitsForCommittedWinnerAndLosesWithoutCleanup() throws Exception {
        var c=queued(2); var audited=new CountDownLatch(1); var release=new CountDownLatch(1); var pid=new AtomicInteger();
        try (var workers=Executors.newFixedThreadPool(2)) {
            var winner=workers.submit(asOwner(()->transitions(event->{
                audit.record(event); pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single()); audited.countDown();
                try { assertThat(release.await(12,TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }).start(c.p(),c.r(),deadline(20))));
            try {
                assertThat(audited.await(5,TimeUnit.SECONDS)).isTrue();
                var loser=workers.submit(asOwner(()->transitions(audit).start(c.p(),c.r(),deadline(12))));
                awaitWait(pid.get(),loser);
                assertThat(state(c)).isEqualTo("QUEUED");
                release.countDown(); assertThat(winner.get(5,TimeUnit.SECONDS)).isNotNull();
                assertThat(loser.get(5,TimeUnit.SECONDS)).isNull(); uncharged(c,"RUNNING"); assertThat(audits(c,RUNNING)).isOne();
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest @ValueSource(strings={"requests", "daily_request_links"})
    void standaloneDispatchMetadataWaitEndsBeforeTableRelease(String table) throws Exception {
        var c = queued(2);
        var submissions = new AtomicInteger();
        var dispatcher = bridge().dispatcher(requests, actors, scope, transitions(audit),
            task -> submissions.incrementAndGet(), (id, left) -> { throw new AssertionError(); });
        try (var worker = Executors.newSingleThreadExecutor(); var holder = source.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int pid = pid(holder);
                try (var lock = holder.createStatement()) {
                    lock.setQueryTimeout(5);
                    lock.execute("LOCK TABLE agent." + table + " IN ACCESS EXCLUSIVE MODE");
                }
                var pending = worker.submit(asOwner(() -> { dispatcher.dispatch(c.p(), c.r()); return null; }));
                awaitWait(pid, pending);
                assertThatThrownBy(() -> pending.get(7, TimeUnit.SECONDS)).hasCauseInstanceOf(AgentUnavailableException.class)
                    .satisfies(error -> assertThat(error.getCause()).hasNoCause());
                assertThat(submissions).hasValue(0);
            } finally { holder.rollback(); }
        }
        uncharged(c, "QUEUED");
        assertThat(audits(c, RUNNING)).isZero();
    }

    @Test void claimWaitEndsBeforeBlockerReleasesRow() throws Exception {
        var c=queued(2); var transitions=transitions(audit);
        try (var worker=Executors.newSingleThreadExecutor(); var holder=source.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int pid=pid(holder); lockRequest(holder,c);
                var pending=worker.submit(asOwner(()->transitions.start(c.p(),c.r(),deadline(2))));
                awaitWait(pid,pending);
                assertThatThrownBy(()->pending.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(LiveResponsePort.CallFailure.class)
                    .satisfies(error->assertThat(error.getCause()).hasMessage("MODEL_TIMEOUT"));
                uncharged(c,"QUEUED"); assertThat(audits(c,RUNNING)).isZero();
            } finally { holder.rollback(); }
        }
    }
}
