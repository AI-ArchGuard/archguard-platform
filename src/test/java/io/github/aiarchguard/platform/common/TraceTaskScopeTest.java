package io.github.aiarchguard.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceTaskScopeTest {
    private static final String TRACE = "1234567890abcdef1234567890abcdef";
    private final TraceTaskScope scope = new TraceTaskScope();

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    void executesWithOnlyPersistedTraceAndRestoresCompleteWorkerMdc() {
        Map<String, String> original = Map.of("traceId", "previous", "worker", "private", "other", "not-forwarded");
        MDC.setContextMap(original);
        scope.wrap(TRACE, () -> {
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("traceId", TRACE));
            assertThat(new MdcTraceIdProvider().currentTraceId()).isEqualTo(TRACE);
            MDC.put("taskOnly", "must-clear");
        }).run();
        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(original);
    }

    @Test
    void restoresMdcEvenWhenTaskFails() {
        MDC.put("worker", "original");
        RuntimeException failure = new IllegalStateException("test failure");
        assertThatThrownBy(() -> scope.wrap(TRACE, () -> {
            MDC.put("task", "discard");
            throw failure;
        }).run()).isSameAs(failure);
        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("worker", "original"));
    }

    @Test
    void noPreviousMdcLeavesNoTraceOrTaskValues() {
        scope.wrap(TRACE, () -> MDC.put("task", "discard")).run();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void invalidTraceAndMissingTaskAreRejectedBeforeAnyExecution() {
        AtomicInteger calls = new AtomicInteger();
        for (String trace : new String[] {null, "", "a".repeat(31), "a".repeat(33), "A".repeat(32), "x\n".repeat(16)}) {
            assertThatThrownBy(() -> scope.wrap(trace, calls::incrementAndGet)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> scope.wrap(TRACE, null)).isInstanceOf(NullPointerException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void nestedTraceRestoresTheEnclosingScope() {
        scope.wrap(TRACE, () -> {
            scope.wrap("a".repeat(32), () -> assertThat(MDC.get("traceId")).isEqualTo("a".repeat(32))).run();
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("traceId", TRACE));
        }).run();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
