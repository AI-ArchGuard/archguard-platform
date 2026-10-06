package io.github.aiarchguard.platform.agent.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent")
final class AgentEnablementController {
    private final AgentEnablementOperations operations;
    private final ObjectMapper mapper;
    AgentEnablementController(AgentEnablementOperations operations, ObjectMapper mapper) {
        this.operations = operations;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
    @GetMapping("/settings") ResponseEntity<AgentSettingsView> settings(@PathVariable UUID projectId) {
        return response(operations.settings(projectId));
    }
    @PutMapping(value = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AgentSettingsView> update(@PathVariable UUID projectId,
            @RequestHeader(value = "If-Match", required = false) String condition, HttpServletRequest request) {
        authorize(projectId, request);
        if (condition == null) throw new AgentSettingsPreconditionException(true);
        long revision;
        try {
            if (!condition.matches("\"(0|[1-9][0-9]{0,18})\"")) throw new AgentInvalidException();
            revision = Long.parseLong(condition.substring(1, condition.length() - 1));
        } catch (RuntimeException invalid) { throw new AgentInvalidException(); }
        return response(operations.update(projectId, revision, read(request, AgentSettingsUpdate.class)));
    }
    @PostMapping(value = "/enablements", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PersonalEnablementView> approve(@PathVariable UUID projectId, HttpServletRequest request) {
        authorize(projectId, request);
        var view = operations.approve(projectId, read(request, PersonalEnablementInput.class));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/agent/enablements/" + view.id())).body(view);
    }
    @GetMapping("/enablements/{enablementId}") PersonalEnablementView get(@PathVariable UUID projectId, @PathVariable UUID enablementId) {
        return operations.get(projectId, enablementId);
    }
    @PostMapping("/enablements/{enablementId}/revoke") PersonalEnablementView revoke(@PathVariable UUID projectId,
            @PathVariable UUID enablementId, HttpServletRequest request) {
        authorize(projectId, request);
        try {
            if (request.getInputStream().read() != -1) throw new AgentInvalidException();
        } catch (java.io.IOException invalid) { throw new AgentInvalidException(); }
        return operations.revoke(projectId, enablementId);
    }
    private ResponseEntity<AgentSettingsView> response(AgentSettingsView view) {
        return ResponseEntity.ok().eTag("\"" + view.revision() + "\"").body(view);
    }
    private void authorize(UUID projectId, HttpServletRequest request) {
        operations.authorizeWrite(projectId, request.getHeader("Origin"), request.isSecure());
    }
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
}
