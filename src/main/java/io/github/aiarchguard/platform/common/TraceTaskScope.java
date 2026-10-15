package io.github.aiarchguard.platform.common;

import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** Isolates a persisted request trace without forwarding unrelated browser or worker MDC values. */
@Component
public final class TraceTaskScope {
    public Runnable wrap(String traceId, Runnable task) {
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid task traceId");
        Objects.requireNonNull(task);
        return () -> {
            var previous = MDC.getCopyOfContextMap();
            MDC.clear();
            MDC.put(MdcTraceIdProvider.MDC_KEY, traceId);
            try { task.run(); }
            finally {
                MDC.clear();
                if (previous != null) MDC.setContextMap(previous);
            }
        };
    }
}
