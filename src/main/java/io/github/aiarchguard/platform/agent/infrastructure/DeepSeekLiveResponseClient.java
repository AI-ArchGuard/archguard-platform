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

/** Unregistered fixed-protocol component; not an AgentModelPort or a credential/egress policy. */
final class DeepSeekLiveResponseClient implements LiveResponsePort {
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
        if (approvedModelIds == null || approvedModelIds.isEmpty()) throw new CallFailure("MODEL_UNAVAILABLE");
        byte[] response = null;
        try {
            response = transport.post(body, apiKey, remaining);
            var accounting = codec.decodeAccounting(response, approvedModelIds);
            // Provider identifiers are untrusted metadata too, not a safe channel for echoed credentials.
            if (sensitiveIdentifier(accounting.responseId(), apiKey) || sensitiveIdentifier(accounting.actualModelId(), apiKey)) {
                throw new CallFailure("OUTPUT_INVALID");
            }
            var usage = new LiveTokenUsage(accounting.inputTokens(), accounting.outputTokens(), accounting.cachedInputTokens(),
                accounting.inputTokens() + accounting.outputTokens(), 0);
            String raw;
            try {
                raw = codec.decodeOutput(accounting);
                // JSON escapes can hide an exact credential echo until it becomes visible business text.
                if (echoesCurrentCredential(raw, apiKey)) {
                    return new Reply(null, "OUTPUT_INVALID", usage, accounting.responseId(), accounting.actualModelId());
                }
            }
            catch (IllegalArgumentException rejectedOutput) {
                return new Reply(null, "OUTPUT_INVALID", usage, accounting.responseId(), accounting.actualModelId());
            }
            // A JSON object remains untrusted: the execution core owns complete business/citation validation.
            return new Reply(raw, null, usage, accounting.responseId(), accounting.actualModelId());
        } catch (DeepSeekTransportFailure unavailable) {
            throw new CallFailure(unavailable.getMessage());
        } catch (RuntimeException invalidAccounting) {
            throw new CallFailure("OUTPUT_INVALID");
        } finally {
            if (response != null) Arrays.fill(response, (byte) 0);
        }
    }

    private static boolean sensitiveIdentifier(String value, String apiKey) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return value.contains(apiKey) || normalized.startsWith("sk-") || normalized.startsWith("ghp_")
            || normalized.startsWith("github_pat_");
    }

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
            throw new IllegalArgumentException("Invalid DeepSeek business output");
        }
    }
}
