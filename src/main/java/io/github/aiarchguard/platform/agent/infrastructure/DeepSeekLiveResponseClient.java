package io.github.aiarchguard.platform.agent.infrastructure;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 受控真实链使用的固定协议客户端，通过惰性生产适配器创建。先独立校验模型和计费用量，再抽取业务文本；无效业务仍可保留合法费用依据。
 */
final class DeepSeekLiveResponseClient implements LiveResponsePort {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeepSeekLiveResponseClient.class);
    private final DeepSeekResponsesCodec codec;
    private final DeepSeekHttpTransport transport;
    private final ObjectMapper outputMapper;

    DeepSeekLiveResponseClient(ObjectMapper mapper, DeepSeekHttpTransport.Exchange exchange) {
        codec = new DeepSeekResponsesCodec(mapper);
        transport = new DeepSeekHttpTransport(exchange);
        outputMapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override public byte[] prepare(AgentModelPort.ModelInput input) {
        try { return codec.encode(input); }
        catch (RuntimeException invalid) { throw new CallFailure("MODEL_UNAVAILABLE"); }
    }

    @Override public Reply send(byte[] body, String apiKey, Duration remaining, Set<String> approvedModelIds) {
        return send(body, apiKey, remaining, approvedModelIds, io.github.aiarchguard.platform.agent.LiveCostPolicy.VERSION);
    }

    /**
     * 先验证封装、模型与计费用量，后处理业务 JSON；
     * 业务失败仍返回合法 usage，封装/用量或敏感标识失败则不提供可信费用依据，响应字节最终清零。
     */
    @Override public Reply send(byte[] body, String apiKey, Duration remaining, Set<String> approvedModelIds, String priceVersion) {
        if (approvedModelIds == null || approvedModelIds.isEmpty()
                || !io.github.aiarchguard.platform.agent.LiveCostPolicy.supported(priceVersion)) throw new CallFailure("MODEL_UNAVAILABLE");
        byte[] response = null;
        try {
            response = transport.post(body, apiKey, remaining);
            // 不把“响应是 JSON”视为可信；封装、模型与 usage 通过后，才有独立计费依据。
            var accounting = codec.decodeAccounting(response, approvedModelIds, priceVersion);
            // Provider identifiers are untrusted metadata too, not a safe channel for echoed credentials.
            if (sensitiveIdentifier(accounting.responseId(), apiKey) || sensitiveIdentifier(accounting.actualModelId(), apiKey)) {
                throw new DeepSeekResponseRejection(DeepSeekResponseRejection.Reason.PROVIDER_IDENTIFIER_SENSITIVE);
            }
            var usage = new LiveTokenUsage(accounting.inputTokens(), accounting.outputTokens(), accounting.cachedInputTokens(),
                accounting.inputTokens() + accounting.outputTokens(), 0);
            String raw;
            try {
                raw = codec.decodeOutput(accounting);
                // JSON escapes can hide an exact credential echo until it becomes visible business text.
                if (echoesCurrentCredential(raw, apiKey)) {
                    rejected(DeepSeekResponseRejection.Reason.OUTPUT_CREDENTIAL_ECHO);
                    return new Reply(null, "OUTPUT_INVALID", usage, accounting.responseId(), accounting.actualModelId());
                }
            }
            catch (IllegalArgumentException rejectedOutput) {
                rejected(rejectedOutput instanceof DeepSeekResponseRejection classified
                    ? classified.reason() : DeepSeekResponseRejection.Reason.UNCLASSIFIED);
                return new Reply(null, "OUTPUT_INVALID", usage, accounting.responseId(), accounting.actualModelId());
            }
            // A JSON object remains untrusted: the execution core owns complete business/citation validation.
            return new Reply(raw, null, usage, accounting.responseId(), accounting.actualModelId());
        } catch (DeepSeekTransportFailure unavailable) {
            if ("OUTPUT_INVALID".equals(unavailable.getMessage())) rejected(unavailable.reason());
            throw new CallFailure(unavailable.getMessage());
        } catch (DeepSeekResponseRejection invalidAccounting) {
            rejected(invalidAccounting);
            throw new CallFailure("OUTPUT_INVALID");
        } catch (RuntimeException invalidAccounting) {
            rejected(DeepSeekResponseRejection.Reason.UNCLASSIFIED);
            throw new CallFailure("OUTPUT_INVALID");
        } finally {
            if (response != null) Arrays.fill(response, (byte) 0);
        }
    }

    private static void rejected(DeepSeekResponseRejection.Reason reason) {
        // Emit only on the synchronous caller, whose persisted trace is already isolated by TraceTaskScope.
        LOGGER.atWarn().addKeyValue("event", "agent.provider.response.rejected")
            .addKeyValue("stage", reason.stage()).addKeyValue("reason", reason.name())
            .log("Agent provider response rejected");
    }

    private static void rejected(DeepSeekResponseRejection rejection) {
        if (rejection.reason() != DeepSeekResponseRejection.Reason.ENVELOPE_UNKNOWN_FIELDS) {
            rejected(rejection.reason());
            return;
        }
        LOGGER.atWarn().addKeyValue("event", "agent.provider.response.rejected")
            .addKeyValue("stage", rejection.reason().stage()).addKeyValue("reason", rejection.reason().name())
            .addKeyValue("unknownFieldCount", rejection.unknownFieldCount())
            .log("Agent provider response rejected");
    }

    private static boolean sensitiveIdentifier(String value, String apiKey) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return value.contains(apiKey) || normalized.startsWith("sk-") || normalized.startsWith("ghp_")
            || normalized.startsWith("github_pat_");
    }

    /**
     * 在解析后的字符串值和字段名中检查当前 Key 的精确回显，覆盖 JSON 转义；不承诺识别拆分、变形编码或历史 Key。
     */
    private boolean echoesCurrentCredential(String raw, String apiKey) {
        try {
            var nodes = new ArrayDeque<JsonNode>();
            nodes.addLast(outputMapper.readTree(raw));
            while (!nodes.isEmpty()) {
                JsonNode node = nodes.removeFirst();
                if (node.isTextual() && node.textValue().contains(apiKey)) return true;
                if (node.isObject()) {
                    for (var field : node.properties()) {
                        if (field.getKey().contains(apiKey)) return true;
                        nodes.addLast(field.getValue());
                    }
                } else if (node.isArray()) node.forEach(nodes::addLast);
            }
            return false;
        } catch (Exception rejectedOutput) {
            // Never expose a parser exception, the credential, or the rejected business body.
            throw new DeepSeekResponseRejection(DeepSeekResponseRejection.Reason.OUTPUT_JSON);
        }
    }
}
