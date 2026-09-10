package io.github.aiarchguard.platform.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TraceIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Request-Id";
    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String previousTraceId = MDC.get(MdcTraceIdProvider.MDC_KEY);
        String traceId = resolveTraceId(request.getHeader(HEADER_NAME));
        MDC.put(MdcTraceIdProvider.MDC_KEY, traceId);
        response.setHeader(HEADER_NAME, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousTraceId == null) {
                MDC.remove(MdcTraceIdProvider.MDC_KEY);
            } else {
                MDC.put(MdcTraceIdProvider.MDC_KEY, previousTraceId);
            }
        }
    }

    private String resolveTraceId(String candidate) {
        if (candidate != null && VALID_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
