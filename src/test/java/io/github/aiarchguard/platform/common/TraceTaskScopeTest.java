package io.github.aiarchguard.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 验证异步任务仅携带持久化 traceId，并在正常、异常和嵌套调用后恢复完整 MDC。这里只检查线程日志上下文，不验证网络传播。
 */
class TraceTaskScopeTest {
    private static final String TRACE = "1234567890abcdef1234567890abcdef";
    private final TraceTaskScope scope = new TraceTaskScope();

    @AfterEach
    void clearMdc() { MDC.clear(); }

    @Test
    // 任务内隔离无关 MDC，完成后完整恢复 worker 原值。
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
    // 抛错仍执行恢复，避免后续任务日志污染。
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
    // 非法 trace 或缺少任务在执行用户代码前拒绝。
    void invalidTraceAndMissingTaskAreRejectedBeforeAnyExecution() {
        AtomicInteger calls = new AtomicInteger();
        for (String trace : new String[] {null, "", "a".repeat(31), "a".repeat(33), "A".repeat(32), "x\n".repeat(16)}) {
            assertThatThrownBy(() -> scope.wrap(trace, calls::incrementAndGet)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> scope.wrap(TRACE, null)).isInstanceOf(NullPointerException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    // 内层退出后恢复外层 trace，而不是清空所有上下文。
    void nestedTraceRestoresTheEnclosingScope() {
        scope.wrap(TRACE, () -> {
            scope.wrap("a".repeat(32), () -> assertThat(MDC.get("traceId")).isEqualTo("a".repeat(32))).run();
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("traceId", TRACE));
        }).run();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
