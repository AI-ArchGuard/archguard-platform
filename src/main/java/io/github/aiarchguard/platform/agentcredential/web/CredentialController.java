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

/** 只写 Key 的 HTTP 入口：先授权再有界解析请求，所有成功响应只包含状态元数据。 */
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

    /** GET 不要求携带 Origin，但若携带仍须匹配，且必须是配置 owner。 */
    @GetMapping CredentialStatus status(HttpServletRequest request) { authorize(request, false); return operations.status(); }

    /** 授权后最多读 513 字节检测 512 上限，拒绝未知/重复字段及尾随 JSON，清零请求字节。 */
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

    /** 删除要求精确 Origin，删除托管文件不等于在提供方撤销账户 Key。 */
    @DeleteMapping CredentialStatus delete(HttpServletRequest request) { authorize(request, true); return operations.delete(); }

    /** 使用容器实际传输安全状态，不自行信任任意转发头声称 HTTPS。 */
    private void authorize(HttpServletRequest request, boolean mutation) {
        operations.authorize(request.getHeader("Origin"), request.isSecure(), mutation);
    }

    /** 明确请求 DTO；覆盖 toString 防止常规对象输出泄露 Key。 */
    record WriteRequest(String apiKey) {
        @Override public String toString() { return "WriteRequest[REDACTED]"; }
    }
}
