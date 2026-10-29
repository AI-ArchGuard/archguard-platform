package io.github.aiarchguard.platform.common;

import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 为后台任务只建立已持久化的请求 traceId，不传播浏览器或线程池遗留的其他 MDC 值。 */
@Component
public final class TraceTaskScope {
    /** 包装时验证格式；执行时清空上下文，finally 完整恢复原线程状态，防止串线。 */
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
