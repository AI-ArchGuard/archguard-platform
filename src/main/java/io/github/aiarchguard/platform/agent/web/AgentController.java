package io.github.aiarchguard.platform.agent.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentOperations;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.CreateAgentRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原合成 Agent API 的 HTTP 入口：拒绝未知请求字段，创建返回 202 与查询地址，权限、版本绑定和提交后排队由应用服务负责。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/requests")
final class AgentController {
    private final AgentOperations operations;
    private final ObjectMapper mapper;

    AgentController(AgentOperations operations, ObjectMapper mapper) {
        this.operations = operations;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    /** 创建仅提交异步工作，因此返回 202 与请求查询地址；模型执行结果通过后续 GET 读取。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AgentRequestView> create(@PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody String json) {
        CreateAgentRequest request;
        try { request = mapper.readValue(json, CreateAgentRequest.class); }
        catch (JsonProcessingException exception) { throw new AgentInvalidException(); }
        AgentRequestView view = operations.create(projectId, key, request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/projects/" + projectId
            + "/agent/requests/" + view.id())).body(view);
    }

    @GetMapping("/{requestId}")
    AgentRequestView get(@PathVariable UUID projectId, @PathVariable UUID requestId) {
        return operations.get(projectId, requestId);
    }
}
