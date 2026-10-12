package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Wrong routing must never turn a non-synthetic request into synthetic work. */
class AgentWorkerIsolationTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<AuditEvent> audit = new ArrayList<>();
    private final AtomicInteger reservations = new AtomicInteger(), settlements = new AtomicInteger(), calls = new AtomicInteger();
    private final MemoryRequests store = new MemoryRequests();
    private boolean linked, linksUnavailable;
    private final AgentRequestRouting routing = new AgentRequestRouting(new LiveRequestStore() {
        public void insert(Link link) { throw new AssertionError("Routing cannot write links"); }
        public Optional<Link> find(UUID project, UUID request) {
            if (linksUnavailable) throw new IllegalStateException("Synthetic link persistence fault");
            return linked ? Optional.of(new Link(request, project, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 0, NOW)) : Optional.empty();
        }
    });
    private final AgentBudgetStore budgets = new AgentBudgetStore() {
        public boolean reserve(UUID project, LocalDate day, long estimate) { reservations.incrementAndGet(); return true; }
        public void settle(UUID project, LocalDate day, long estimate, long actual) { settlements.incrementAndGet(); }
    };
    private final AgentTransitions transitions = new AgentTransitions(store, budgets, audit::add, clock, routing);
    private final AgentModelPort model = new AgentModelPort() {
        public boolean syntheticOnly() { return true; }
        public boolean available() { return true; }
        public ModelResponse explain(ModelInput input) {
            calls.incrementAndGet();
            return new ModelResponse("{}", 1, 1, 1, "synthetic-response", "synthetic-model");
        }
    };
    private final AgentWorker worker = new AgentWorker(store, transitions, model, new AgentOutputValidator(mapper),
        port(ProjectAuthorization.class), port(ReportSubmissionOperations.class), mapper, clock, routing, Duration.ofSeconds(1));

    @ParameterizedTest
    @ValueSource(strings = {"live-profile", "profile", "protocol", "model", "price", "schema", "prompt",
        "input-prompt", "input-schema", "input-purpose", "missing-bindings", "missing-input"})
    void queuedNonSyntheticOrMalformedBindingCannotReachSyntheticLedgerOrModel(String change) {
        store.value = snapshot("QUEUED", change);
        AgentSnapshot before = store.value;

        worker.process(before.view().projectId(), before.view().id());

        assertUntouched(before);
    }

    @Test void queueRejectionCannotTerminalizeALiveRequest() {
        store.value = snapshot("QUEUED", "live-profile");
        AgentSnapshot before = store.value;

        worker.failQueued(before.view().projectId(), before.view().id(), "MODEL_UNAVAILABLE");

        assertUntouched(before);
    }

    @Test void workerExceptionCannotTerminalizeALiveRequestThroughFailUnknown() {
        store.value = snapshot("RUNNING", "live-profile");
        store.failNextRead = true;
        AgentSnapshot before = store.value;

        worker.process(before.view().projectId(), before.view().id());

        assertUntouched(before);
    }

    @Test void directSyntheticTransitionsCannotMutateLiveRequests() {
        store.value = snapshot("QUEUED", "live-profile");
        AgentSnapshot queued = store.value;
        assertThat(transitions.start(queued, 4000)).isNull();
        transitions.failQueued(queued, "MODEL_UNAVAILABLE");
        transitions.failUnknown(queued);
        assertUntouched(queued);

        store.value = snapshot("RUNNING", "live-profile");
        AgentSnapshot running = store.value;
        transitions.finish(running, new AgentTransitions.Start(LocalDate.of(2026, 10, 12), 4000), null,
            new AgentRequestView.AgentFailure("OUTPUT_INVALID", "Synthetic failure"), null, false);
        transitions.failUnknown(running);
        assertUntouched(running);
    }

    @Test void liveAssociationCannotBeDisguisedAsSyntheticBindings() {
        linked = true;
        store.value = snapshot("QUEUED", "unchanged");
        AgentSnapshot before = store.value;
        worker.process(before.view().projectId(), before.view().id());
        worker.failQueued(before.view().projectId(), before.view().id(), "MODEL_UNAVAILABLE");
        assertThat(transitions.start(before, 4000)).isNull();
        transitions.failQueued(before, "MODEL_UNAVAILABLE");
        transitions.failUnknown(before);
        assertUntouched(before);

        store.value = snapshot("RUNNING", "unchanged");
        AgentSnapshot running = store.value;
        transitions.finish(running, new AgentTransitions.Start(LocalDate.of(2026, 10, 12), 4000), null,
            new AgentRequestView.AgentFailure("OUTPUT_INVALID", "Synthetic failure"), null, false);
        transitions.failUnknown(running);
        assertUntouched(running);
    }

    @Test void unavailableAssociationReadCannotFallBackToSyntheticWork() {
        linksUnavailable = true;
        store.value = snapshot("QUEUED", "unchanged");
        AgentSnapshot before = store.value;

        worker.process(before.view().projectId(), before.view().id());

        assertUntouched(before);
    }

    @Test void unchangedSyntheticRequestStillUsesItsOwnWorkerAndLedger() {
        store.value = snapshot("QUEUED", "unchanged");

        worker.process(store.value.view().projectId(), store.value.view().id());

        assertThat(calls.get()).isOne();
        assertThat(reservations.get()).isOne();
        assertThat(settlements.get()).isOne();
        assertThat(store.value.view().state()).isEqualTo("FAILED");
        assertThat(store.value.view().failure().code()).isEqualTo("OUTPUT_INVALID");
        assertThat(audit).hasSize(2);
    }

    private void assertUntouched(AgentSnapshot before) {
        assertThat(store.value).isSameAs(before);
        assertThat(store.writes.get()).isZero();
        assertThat(calls.get()).isZero();
        assertThat(reservations.get()).isZero();
        assertThat(settlements.get()).isZero();
        assertThat(audit).isEmpty();
    }

    private static AgentSnapshot snapshot(String state, String change) {
        String prompt = "finding-explanation-0.1.0";
        var bindings = new AgentRequestView.VersionBindings(UUID.randomUUID(), "a".repeat(64), null,
            List.of(UUID.randomUUID()), List.of(), "prompt".equals(change) ? "unknown-prompt" : prompt,
            "live-profile".equals(change) ? LiveAccountingOperations.MODEL_PROFILE
                : "profile".equals(change) ? "unknown-profile" : "disabled-or-test-fake-0.1.0",
            "protocol".equals(change) ? "unknown-protocol" : "responses-v1-restricted",
            "model".equals(change) ? "deepseek-flash" : "deterministic-fake-or-disabled",
            "schema".equals(change) ? "unknown-schema" : "0.1.0",
            "price".equals(change) ? LiveCostPolicy.VERSION : "synthetic-0.1.0", "b".repeat(64));
        var view = new AgentRequestView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "c".repeat(32),
            "FINDING_EXPLANATION", state, "missing-bindings".equals(change) ? null : bindings,
            null, null, null, NOW, NOW);
        var input = new AgentModelPort.ModelInput("input-purpose".equals(change) ? "PR_SUMMARY" : view.purpose(),
            "input-schema".equals(change) ? "unknown-schema" : "0.1.0",
            "input-prompt".equals(change) ? "unknown-prompt" : prompt,
            "finding", "synthetic.rule", "0.1.0", "high", "Synthetic finding", "synthetic.module", List.of(), List.of(), null);
        return new AgentSnapshot(view, "missing-input".equals(change) ? null : input, List.of());
    }

    @SuppressWarnings("unchecked")
    private static <T> T port(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) ->
            method.getReturnType().equals(boolean.class) ? true : null);
    }

    static final class MemoryRequests implements AgentStore {
        AgentSnapshot value;
        boolean failNextRead;
        final AtomicInteger writes = new AtomicInteger();
        public Optional<AgentSnapshot> find(UUID project, UUID request) {
            if (failNextRead) { failNextRead = false; throw new IllegalStateException("Synthetic persistence fault"); }
            return Optional.ofNullable(value);
        }
        public boolean claim(UUID request, Instant at) {
            writes.incrementAndGet();
            if (!"QUEUED".equals(value.view().state())) return false;
            replace("RUNNING", null, null, null, at); return true;
        }
        public void setReservation(UUID request, LocalDate day, long estimate) { writes.incrementAndGet(); }
        public boolean failQueued(UUID request, AgentRequestView.AgentFailure failure, Instant at) {
            writes.incrementAndGet();
            if (!"QUEUED".equals(value.view().state())) return false;
            replace("FAILED", null, failure, null, at); return true;
        }
        public boolean failRunning(UUID request, AgentRequestView.AgentFailure failure, Instant at) {
            writes.incrementAndGet();
            if (!"RUNNING".equals(value.view().state())) return false;
            replace("FAILED", null, failure, null, at); return true;
        }
        public boolean finish(UUID request, AgentRequestView.AgentResult result, AgentRequestView.AgentFailure failure,
                AgentRequestView.AgentUsage usage, Instant at) {
            writes.incrementAndGet();
            if (!"RUNNING".equals(value.view().state())) return false;
            replace(result == null ? "FAILED" : "SUCCEEDED", result, failure, usage, at); return true;
        }
        private void replace(String state, AgentRequestView.AgentResult result, AgentRequestView.AgentFailure failure,
                AgentRequestView.AgentUsage usage, Instant at) {
            var view = value.view();
            value = new AgentSnapshot(new AgentRequestView(view.id(), view.projectId(), view.requesterId(), view.traceId(),
                view.purpose(), state, view.bindings(), result, failure, usage, view.createdAt(), at), value.input(), value.candidates());
        }
        public boolean insert(AgentSnapshot snapshot, String key) { throw new AssertionError(); }
        public Optional<AgentSnapshot> findByKey(UUID project, UUID requester, String key) { throw new AssertionError(); }
        public boolean hasRequests(UUID project) { throw new AssertionError(); }
        public List<AgentSnapshot> expired(Instant cutoff, int limit) { throw new AssertionError(); }
        public boolean expire(UUID request, Instant cutoff, AgentRequestView.AgentFailure failure, Instant at) { throw new AssertionError(); }
    }
}
