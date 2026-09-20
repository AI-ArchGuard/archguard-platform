package io.github.aiarchguard.platform.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ApiErrorWriter {
    private final ObjectMapper objectMapper;
    private final TraceIdProvider traceIdProvider;

    public ApiErrorWriter(ObjectMapper objectMapper, TraceIdProvider traceIdProvider) {
        this.objectMapper = objectMapper;
        this.traceIdProvider = traceIdProvider;
    }

    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
            new ApiError(code, message, traceIdProvider.currentTraceId(), Map.of()));
    }
}
