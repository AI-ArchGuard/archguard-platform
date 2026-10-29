package io.github.aiarchguard.platform.agent.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentEnablementOperations;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.LiveBatchInput;
import io.github.aiarchguard.platform.agent.LiveBatchPreviewInput;
import io.github.aiarchguard.platform.agent.LiveBatchPreviewView;
import io.github.aiarchguard.platform.agent.LiveBatchView;
import io.github.aiarchguard.platform.agent.LiveBatchExecutionOperations;
import io.github.aiarchguard.platform.agent.LiveBatchExecutionView;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingOperations;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 批次元数据的 HTTP 边界，负责先授权再有界解析、预览、批准、读取及撤销。费用预留和模型发送不通过本控制器开放。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/batches")
final class AgentBatchController {
    private final LiveAccountingOperations accounting;
    private final LiveBatchExecutionOperations execution;
    private final AgentEnablementOperations controls;
    private final AuditRecorder audit;
    private final CurrentActorProvider actors;
    private final TraceIdProvider traces;
    private final ObjectMapper mapper;

    AgentBatchController(LiveAccountingOperations accounting, LiveBatchExecutionOperations execution, AgentEnablementOperations controls,
            AuditRecorder audit, CurrentActorProvider actors, TraceIdProvider traces, ObjectMapper mapper) {
        this.accounting = accounting; this.execution = execution; this.controls = controls; this.audit = audit;
        this.actors = actors; this.traces = traces;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    /** 写入式预览入口先复用个人控制授权，再有界解析模板选择；返回的摘要只供后续精确批准比较。 */
    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    LiveBatchPreviewView preview(@PathVariable UUID projectId, HttpServletRequest request) {
        return boundary(projectId, () -> {
            authorize(projectId, request);
            var input = read(request, LiveBatchPreviewInput.class);
            String hash = accounting.preview(projectId, input.templateRequestIds());
            return new LiveBatchPreviewView("0.1.0", hash, input.templateRequestIds().size());
        });
    }

    /** 控制器仅审计授权/解析拒绝；应用服务拥有批准事务及其失败审计，避免同一次失败重复计数。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LiveBatchView> approve(@PathVariable UUID projectId, HttpServletRequest request) {
        var input = boundary(projectId, () -> {
            authorize(projectId, request);
            return read(request, LiveBatchInput.class);
        });
        // Application failures are already audited after the accounting transaction rolls back.
        var view = accounting.approve(projectId, request.getHeader("Origin"), request.isSecure(), input);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/agent/batches/" + view.id())).body(view);
    }

    @GetMapping("/{batchId}")
    LiveBatchView get(@PathVariable UUID projectId, @PathVariable UUID batchId) {
        return accounting.get(projectId, batchId);
    }

    @GetMapping("/{batchId}/execution")
    LiveBatchExecutionView execution(@PathVariable UUID projectId, @PathVariable UUID batchId) {
        return execution.get(projectId, batchId);
    }

    @PostMapping("/{batchId}/revoke")
    LiveBatchView revoke(@PathVariable UUID projectId, @PathVariable UUID batchId, HttpServletRequest request) {
        boundary(projectId, () -> {
            authorize(projectId, request);
            requireEmpty(request);
            return null;
        });
        return accounting.revoke(projectId, batchId, request.getHeader("Origin"), request.isSecure());
    }

    private void authorize(UUID project, HttpServletRequest request) {
        controls.authorizeWrite(project, request.getHeader("Origin"), request.isSecure());
    }

    /** 记录 HTTP 边界拒绝的固定原因和状态；审计失败显式转不可用，不记录请求体或异常原文。 */
    private <T> T boundary(UUID project, Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException failure) {
            var error = BatchExceptionHandler.classify(failure);
            AuditResult result = error.status() == HttpStatus.FORBIDDEN ? AuditResult.DENIED
                : error.status() == HttpStatus.CONFLICT ? AuditResult.CONFLICT : AuditResult.FAILURE;
            try {
                audit.record(new AuditEvent(actors.currentActor().id(), project, "agent.batch.http_rejected", result,
                    traces.currentTraceId(), Map.of("reason", error.code(), "status", error.status().value())));
            } catch (RuntimeException unavailableAudit) { throw new AgentUnavailableException(); }
            throw failure;
        }
    }

    /**
     * 读取 8 KiB 加一个探测字节以识别越界；严格 mapper 拒绝未知/重复字段、尾随 JSON 和标量强制转换。
     */
    private <T> T read(HttpServletRequest request, Class<T> type) {
        byte[] bytes = null;
        try {
            bytes = request.getInputStream().readNBytes(8193);
            if (bytes.length > 8192) throw new AgentInvalidException();
            T parsed = mapper.readValue(bytes, type);
            if (parsed == null) throw new AgentInvalidException();
            return parsed;
        } catch (Exception invalid) { throw new AgentInvalidException(); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    private void requireEmpty(HttpServletRequest request) {
        try {
            if (request.getInputStream().read() != -1) throw new AgentInvalidException();
        } catch (java.io.IOException invalid) { throw new AgentInvalidException(); }
    }
}
