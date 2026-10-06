package io.github.aiarchguard.platform.agentcredential.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.agentcredential.CredentialOperations;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/credentials/deepseek")
final class CredentialController {
    private final CredentialOperations operations;
    private final ObjectMapper mapper;
    CredentialController(CredentialOperations operations, ObjectMapper mapper) {
        this.operations = operations;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @GetMapping CredentialStatus status(HttpServletRequest request) { authorize(request, false); return operations.status(); }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    CredentialStatus write(HttpServletRequest request) {
        authorize(request, true);
        byte[] bytes = null;
        WriteRequest parsed;
        try {
            bytes = request.getInputStream().readNBytes(513);
            if (bytes.length > 512) throw new CredentialFailure(CredentialFailure.Kind.INVALID);
            parsed = mapper.readValue(bytes, WriteRequest.class);
            if (parsed == null) throw new CredentialFailure(CredentialFailure.Kind.INVALID);
        } catch (Exception failure) {
            operations.rejectInvalidWrite();
            throw new CredentialFailure(CredentialFailure.Kind.INVALID);
        }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
        return operations.write(parsed.apiKey());
    }

    @DeleteMapping CredentialStatus delete(HttpServletRequest request) { authorize(request, true); return operations.delete(); }

    private void authorize(HttpServletRequest request, boolean mutation) {
        operations.authorize(request.getHeader("Origin"), request.isSecure(), mutation);
    }

    record WriteRequest(String apiKey) {
        @Override public String toString() { return "WriteRequest[REDACTED]"; }
    }
}
