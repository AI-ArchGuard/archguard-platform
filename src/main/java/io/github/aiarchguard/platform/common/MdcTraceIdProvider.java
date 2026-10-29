package io.github.aiarchguard.platform.common;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 从当前线程 MDC 读取追踪标识；缺失时拒绝伪造值，便于发现未建立任务范围的调用。 */
@Component
final class MdcTraceIdProvider implements TraceIdProvider {
    static final String MDC_KEY = "traceId";

    @Override
    public String currentTraceId() {
        String traceId = MDC.get(MDC_KEY);
        if (traceId == null) {
            throw new IllegalStateException("No traceId is available for the current request");
        }
        return traceId;
    }
}
