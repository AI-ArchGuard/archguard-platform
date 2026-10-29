package io.github.aiarchguard.platform.agent.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentLiveRunOperations;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.LiveAgentRunInput;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 独立真实请求 HTTP 入口。先确认身份、项目及部署准入，再读取两个选择 ID；
 * 严格限制头部、正文大小与 UUID 表示，并返回不可缓存的 202。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/live-requests")
final class AgentLiveController {
    private final AgentLiveRunOperations operations;
    private final ObjectMapper mapper;
    private final AuditRecorder audit;
    private final CurrentActorProvider actors;
    private final TraceIdProvider traces;

    AgentLiveController(AgentLiveRunOperations operations, ObjectMapper mapper, AuditRecorder audit,
            CurrentActorProvider actors, TraceIdProvider traces) {
        this.operations = operations; this.audit = audit; this.actors = actors; this.traces = traces;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    /**
     * 在读取正文前完成身份及部署检查，并要求唯一 Origin/幂等头；创建/调度失败审计由应用层负责，控制器只覆盖自身边界拒绝。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AgentRequestView> create(@PathVariable UUID projectId, HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        var parsed = boundary(projectId, () -> {
            // Identity, current Project/owner, origin and deployment readiness precede all body reads.
            operations.authorize(projectId, origin, request.isSecure());
            if (Collections.list(request.getHeaders("Origin")).size() != 1) throw new AgentInvalidException();
            var keys = Collections.list(request.getHeaders("Idempotency-Key"));
            if (keys.size() != 1) throw new AgentInvalidException();
            String key = keys.getFirst();
            if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(c -> c < 33 || c > 126)) {
                throw new AgentInvalidException();
            }
            return new Parsed(read(request), key);
        });
        // The application owns/audits create or dispatch failures; do not double-count them here.
        var view = operations.run(projectId, parsed.input(), parsed.key(), origin, request.isSecure());
        return ResponseEntity.accepted().location(URI.create("/api/v1/projects/" + projectId + "/agent/requests/" + view.id()))
            .header("Cache-Control", "no-store").header("Pragma", "no-cache").body(view);
    }

    /** JSON 必须恰好包含两个选择 ID；不接受用户提供模型输入、凭据、价格或目标地址。 */
    private LiveAgentRunInput read(HttpServletRequest request) {
        byte[] bytes = null;
        try {
            bytes = request.getInputStream().readNBytes(8193);
            if (bytes.length > 8192) throw new AgentInvalidException();
            JsonNode input = mapper.readTree(bytes);
            if (input == null || !input.isObject() || input.size() != 2
                    || !input.has("batchId") || !input.has("templateRequestId")) throw new AgentInvalidException();
            return new LiveAgentRunInput(identifier(input.get("batchId")), identifier(input.get("templateRequestId")));
        } catch (Exception invalid) { throw new AgentInvalidException(); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    /** 拒绝 UUID.fromString 可接受的缩短分组表示，以规范 UUID 文本封住同一 ID 多种输入表示。 */
    private UUID identifier(JsonNode value) {
        if (value == null || !value.isTextual()) throw new AgentInvalidException();
        String text = value.textValue();
        UUID id = UUID.fromString(text);
        // UUID.fromString accepts shortened groups; Jackson also accepts base64 UUID strings.
        if (!id.toString().equalsIgnoreCase(text)) throw new AgentInvalidException();
        return id;
    }

    private <T> T boundary(UUID project, Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException failure) {
            var error = BatchExceptionHandler.classify(failure);
            AuditResult result = error.status() == HttpStatus.FORBIDDEN ? AuditResult.DENIED
                : error.status() == HttpStatus.CONFLICT ? AuditResult.CONFLICT : AuditResult.FAILURE;
            try {
                audit.record(new AuditEvent(actors.currentActor().id(), project, "agent.live.http_rejected", result,
                    traces.currentTraceId(), Map.of("reason", error.code(), "status", error.status().value())));
            } catch (RuntimeException unavailableAudit) { throw new AgentUnavailableException(); }
            throw failure;
        }
    }

    private record Parsed(LiveAgentRunInput input, String key) { }
}
