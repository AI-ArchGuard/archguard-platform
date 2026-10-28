package io.github.aiarchguard.platform.common;

/** 业务与审计获取当前追踪标识的公共端口，不依赖 Servlet 或 MDC 的具体实现。 */
public interface TraceIdProvider {
    String currentTraceId();
}
