package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeepSeekLiveResponseClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void explicitContextVersionPreservesOverThresholdAccountingWithoutRelaxingLegacyOrUnknownPolicies() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var client = new DeepSeekLiveResponseClient(mapper, (request, timeout) -> {
            calls.incrementAndGet(); var body = valid();
            ((ObjectNode) body.path("usage")).put("input_tokens", 8001).put("total_tokens", 8021);
            return new DeepSeekHttpTransport.Response(200, "application/json", mapper.writeValueAsBytes(body));
        });
        byte[] body = client.prepare(input("Synthetic context usage"));
        var reply = client.send(body, "synthetic-test-key", Duration.ofMillis(500), Set.of("deepseek-flash"),
            io.github.aiarchguard.platform.agent.LiveCostPolicy.CONTEXT_VERSION);
        assertThat(reply.usage().inputTokens()).isEqualTo(8001);
        assertThatThrownBy(() -> client.send(body, "synthetic-test-key", Duration.ofMillis(500), Set.of("deepseek-flash")))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("OUTPUT_INVALID");
        assertThat(calls.get()).isEqualTo(2);
        assertThatThrownBy(() -> client.send(body, "synthetic-test-key", Duration.ofMillis(500), Set.of("deepseek-flash"), "unapproved"))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void preparesOnlyFrozenInputAndUsesOneFixedOfficialRequestWithoutModelPortRegistration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<java.net.http.HttpRequest> captured = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();
        var client = new DeepSeekLiveResponseClient(mapper, (request, timeout) -> {
            calls.incrementAndGet(); captured.set(request);
            assertThat(timeout).isEqualTo(Duration.ofMillis(321));
            byte[] response = mapper.writeValueAsBytes(valid()); received.set(response);
            return new DeepSeekHttpTransport.Response(200, "application/json", response);
        });
        byte[] request = client.prepare(input("untrusted document text"));
        var encoded = mapper.readTree(request);
        assertThat(encoded.properties().stream().map(java.util.Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
            "model", "instructions", "input", "text", "max_output_tokens", "reasoning", "tool_choice", "store");
        var reply = client.send(request, "synthetic-test-key", Duration.ofMillis(321), Set.of("deepseek-flash"));
        assertThat(reply.rawJson()).isEqualTo("{\"unverified\":true}");
        assertThat(reply.outputFailureCode()).isNull();
        assertThat(reply.usage().inputTokens()).isEqualTo(100);
        assertThat(reply.usage().outputTokens()).isEqualTo(20);
        assertThat(reply.usage().cachedInputTokens()).isEqualTo(40);
        assertThat(reply.usage().totalTokens()).isEqualTo(120);
        assertThat(reply.usage().reasoningTokens()).isZero();
        assertThat(reply.providerResponseId()).isEqualTo("resp-synthetic-1");
        assertThat(reply.actualModelId()).isEqualTo("deepseek-flash");
        assertThat(reply.toString()).doesNotContain("unverified", "synthetic-test-key");
        assertThat(captured.get().uri()).isEqualTo(URI.create("https://api.deepseek.com/responses"));
        assertThat(captured.get().headers().firstValue("Authorization")).contains("Bearer synthetic-test-key");
        assertThat(calls.get()).isOne();
        assertThat(received.get()).containsOnly((byte) 0);
        assertThat(AgentModelPort.class.isAssignableFrom(DeepSeekLiveResponseClient.class)).isFalse();
        assertThat(DeepSeekLiveResponseClient.class.getAnnotations()).isEmpty();
    }

    @Test void rejectedOutputKeepsValidatedUsageAndNeverRetainsRejectedText() throws Exception {
        for (String raw : List.of("private-secret not-json", "[1,2]", "null", "{} {}", "{\"x\":1,\"x\":2}")) {
            var root = valid(); ((ObjectNode) root.at("/output/0/content/0")).put("text", raw);
            assertRejectedOutput(root);
        }
        var refusal = valid(); ((ObjectNode) refusal.at("/output/0/content/0"))
            .put("type", "refusal").put("refusal", "private-secret"); assertRejectedOutput(refusal);
        var tool = valid(); ((ObjectNode) tool.at("/output/0")).put("type", "function_call"); assertRejectedOutput(tool);
        var annotations = valid(); ((ObjectNode) annotations.at("/output/0/content/0"))
            .putArray("annotations").addObject().put("url", "https://untrusted.invalid"); assertRejectedOutput(annotations);
        var missing = valid(); missing.remove("output"); assertRejectedOutput(missing);
    }

    @Test void untrustedEnvelopeModelAndUsageNeverProduceBillableUsageOrLeakyErrors() throws Exception {
        var model = valid().put("model", "other-model"); assertUntrusted(model.toString());
        var unknown = valid().put("private-secret", "private-secret"); assertUntrusted(unknown.toString());
        var incomplete = valid().put("status", "incomplete"); assertUntrusted(incomplete.toString());
        var cache = valid(); ((ObjectNode) cache.at("/usage/input_tokens_details")).put("cached_tokens", 101);
        assertUntrusted(cache.toString());
        var total = valid(); ((ObjectNode) total.path("usage")).put("total_tokens", 121); assertUntrusted(total.toString());
        var missing = valid(); missing.remove("usage"); assertUntrusted(missing.toString());
        assertUntrusted("private-secret not-json");
        assertUntrusted(valid() + " {}");
        assertUntrusted(valid().toString().replace("\"status\":\"completed\"", "\"status\":\"completed\",\"status\":\"completed\""));
    }

    @Test void credentialShapedOrEchoedResponseIdentifiersNeverLeaveTheClientAsMetadata() throws Exception {
        for (String id : List.of("synthetic-test-key", "resp-synthetic-test-key", "sk-synthetic-sensitive-id", "SK-synthetic-sensitive-id",
                "ghp_syntheticSensitive", "github_pat_syntheticSensitive")) {
            byte[] bytes = mapper.writeValueAsBytes(valid().put("id", id));
            AtomicInteger calls = new AtomicInteger();
            var client = new DeepSeekLiveResponseClient(mapper, (request, timeout) -> {
                calls.incrementAndGet(); return new DeepSeekHttpTransport.Response(200, "application/json", bytes);
            });
            assertThatThrownBy(() -> client.send(new byte[1], "synthetic-test-key", Duration.ofSeconds(1), Set.of("deepseek-flash")))
                .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("OUTPUT_INVALID").hasNoCause();
            assertThat(calls.get()).isOne(); assertThat(bytes).containsOnly((byte) 0);
        }
    }

    @Test void exactCurrentCredentialInBusinessStringFieldsIsRejectedWithoutLosingBillableUsage() throws Exception {
        for (String field : List.of("conclusion", "claims", "ruleBasis", "suggestions", "limitations")) {
            assertRejectedCredentialEcho(businessOutput(field, "prefix synthetic-test-key suffix"));
        }
    }

    @Test void unicodeEscapedCurrentCredentialIsRejectedAfterBusinessJsonDecoding() throws Exception {
        String key = "synthetic-test-key";
        StringBuilder escaped = new StringBuilder();
        for (char character : key.toCharArray()) escaped.append(String.format("\\u%04x", (int) character));
        for (String field : List.of("conclusion", "claims", "ruleBasis", "suggestions", "limitations")) {
            String raw = businessOutput(field, "prefix " + key + " suffix").replace(key, escaped);
            assertThat(raw.contains(key)).as("the raw JSON must not contain the literal credential").isFalse();
            String pointer = field.equals("conclusion") ? "/conclusion/text"
                : field.equals("limitations") ? "/limitations/0" : "/" + field + "/0/text";
            assertThat(mapper.readTree(raw).at(pointer).textValue().contains(key))
                .as("the decoded JSON string reproduces the exact credential").isTrue();
            assertRejectedCredentialEcho(raw);
        }
    }

    @Test void ordinaryBusinessCredentialPrefixesAndNonExactCredentialsAreNotFiltered() throws Exception {
        for (String content : List.of("sk-placeholder ghp_document github_pat_document",
                "synthetic-other-key", "SYNTHETIC-TEST-KEY")) {
            String raw = businessOutput("conclusion", content);
            var root = valid(); ((ObjectNode) root.at("/output/0/content/0")).put("text", raw);
            byte[] bytes = mapper.writeValueAsBytes(root);
            var client = new DeepSeekLiveResponseClient(mapper,
                (request, timeout) -> new DeepSeekHttpTransport.Response(200, "application/json", bytes));
            var reply = client.send(new byte[1], "synthetic-test-key", Duration.ofSeconds(1), Set.of("deepseek-flash"));
            assertThat(reply.rawJson()).isEqualTo(raw); assertThat(reply.outputFailureCode()).isNull();
            assertThat(reply.usage().totalTokens()).isEqualTo(120);
            assertThat(bytes).containsOnly((byte) 0);
        }
    }

    @Test void noApprovedModelsBadCredentialsLimitsAndInvalidPrepareCauseZeroExchanges() {
        AtomicInteger calls = new AtomicInteger();
        var client = new DeepSeekLiveResponseClient(mapper, (request, timeout) -> { calls.incrementAndGet(); throw new AssertionError(); });
        for (Set<String> models : java.util.Arrays.<Set<String>>asList(null, Set.of())) {
            assertThatThrownBy(() -> client.send(new byte[1], "synthetic-test-key", Duration.ofSeconds(1), models))
                .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        }
        assertThatThrownBy(() -> client.send(new byte[1], "synthetic-test-key\r\nprivate-secret", Duration.ofSeconds(1), Set.of("deepseek-flash")))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> client.send(new byte[6001], "synthetic-test-key", Duration.ofSeconds(1), Set.of("deepseek-flash")))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> client.send(new byte[1], "synthetic-test-key", Duration.ZERO, Set.of("deepseek-flash")))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> client.prepare(input("private-secret".repeat(1000))))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThat(calls.get()).isZero();
    }

    @Test void transportFailuresAreStableAndNeverRetryOrRetainProviderCauses() {
        for (String code : List.of("MODEL_TIMEOUT", "MODEL_UNAVAILABLE", "OUTPUT_INVALID")) {
            AtomicInteger calls = new AtomicInteger();
            var client = new DeepSeekLiveResponseClient(mapper, (request, timeout) -> {
                calls.incrementAndGet(); throw new DeepSeekTransportFailure(code);
            });
            assertThatThrownBy(() -> client.send(new byte[1], "synthetic-test-key", Duration.ofSeconds(1), Set.of("deepseek-flash")))
                .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage(code).hasNoCause();
            assertThat(calls.get()).isOne();
        }
        var client = new DeepSeekLiveResponseClient(mapper, (request, timeout) -> { throw new java.io.IOException("private-secret"); });
        assertThatThrownBy(() -> client.send(new byte[1], "synthetic-test-key", Duration.ofSeconds(1), Set.of("deepseek-flash")))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
    }

    private void assertRejectedOutput(ObjectNode root) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(root);
        var client = new DeepSeekLiveResponseClient(mapper, (request, timeout) -> new DeepSeekHttpTransport.Response(200, "application/json", bytes));
        var reply = client.send(new byte[1], "synthetic-test-key", Duration.ofSeconds(1), Set.of("deepseek-flash"));
        assertThat(reply.rawJson()).isNull(); assertThat(reply.outputFailureCode()).isEqualTo("OUTPUT_INVALID");
        assertThat(reply.usage().inputTokens()).isEqualTo(100); assertThat(reply.usage().cachedInputTokens()).isEqualTo(40);
        assertThat(reply.providerResponseId()).isEqualTo("resp-synthetic-1");
        assertThat(reply.toString()).doesNotContain("private-secret", "untrusted.invalid");
        assertThat(bytes).containsOnly((byte) 0);
    }

    private void assertRejectedCredentialEcho(String raw) throws Exception {
        var root = valid(); ((ObjectNode) root.at("/output/0/content/0")).put("text", raw);
        byte[] bytes = mapper.writeValueAsBytes(root);
        AtomicInteger calls = new AtomicInteger();
        var client = new DeepSeekLiveResponseClient(mapper, (request, timeout) -> {
            calls.incrementAndGet(); return new DeepSeekHttpTransport.Response(200, "application/json", bytes);
        });
        var reply = client.send(new byte[1], "synthetic-test-key", Duration.ofSeconds(1), Set.of("deepseek-flash"));
        assertThat(reply.rawJson() == null).as("no rejected model content is retained").isTrue();
        assertThat(reply.outputFailureCode()).isEqualTo("OUTPUT_INVALID");
        assertThat(reply.usage().inputTokens()).isEqualTo(100);
        assertThat(reply.usage().outputTokens()).isEqualTo(20);
        assertThat(reply.usage().cachedInputTokens()).isEqualTo(40);
        assertThat(reply.usage().totalTokens()).isEqualTo(120);
        assertThat(reply.usage().reasoningTokens()).isZero();
        assertThat(reply.providerResponseId()).isEqualTo("resp-synthetic-1");
        assertThat(reply.actualModelId()).isEqualTo("deepseek-flash");
        assertThat(reply.toString()).doesNotContain("synthetic-test-key", "prefix", "suffix");
        assertThat(calls.get()).isOne(); assertThat(bytes).containsOnly((byte) 0);
    }

    private String businessOutput(String field, String text) throws Exception {
        ObjectNode output = (ObjectNode) mapper.readTree("""
            {"schemaVersion":"0.1.0","purpose":"FINDING_EXPLANATION",
             "conclusion":{"kind":"SUPPORTED","text":"Synthetic evidence supports human verification.","citationIds":["e-synthetic"]},
             "claims":[],"ruleBasis":[],"suggestions":[],"limitations":[]}
            """);
        if (field.equals("conclusion")) ((ObjectNode) output.get(field)).put("text", text);
        else if (field.equals("limitations")) output.putArray(field).add(text);
        else if (field.equals("suggestions")) {
            var suggestion = output.putArray(field).addObject().put("kind", "HUMAN_VERIFICATION")
                .put("text", text).put("requiresHumanReview", true);
            suggestion.putArray("findingRefs").add("f-synthetic"); suggestion.putArray("citationIds").add("e-synthetic");
        } else {
            output.putArray(field).addObject().put("findingRef", "f-synthetic").put("text", text)
                .putArray("citationIds").add("e-synthetic");
        }
        return mapper.writeValueAsString(output);
    }

    private void assertUntrusted(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        var client = new DeepSeekLiveResponseClient(mapper, (request, timeout) -> new DeepSeekHttpTransport.Response(200, "application/json", bytes));
        assertThatThrownBy(() -> client.send(new byte[1], "synthetic-test-key", Duration.ofSeconds(1), Set.of("deepseek-flash")))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("OUTPUT_INVALID").hasNoCause();
        assertThat(bytes).containsOnly((byte) 0);
    }

    private ObjectNode valid() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"id":"resp-synthetic-1","object":"response","status":"completed","model":"deepseek-flash",
             "output":[{"id":"msg-1","type":"message","role":"assistant","status":"completed",
               "content":[{"type":"output_text","text":"{\\"unverified\\":true}","annotations":[]}]}],
             "usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120,
               "input_tokens_details":{"cached_tokens":40},"output_tokens_details":{"reasoning_tokens":0}}}
            """);
    }

    private AgentModelPort.ModelInput input(String excerpt) {
        return new AgentModelPort.ModelInput("FINDING_EXPLANATION", "0.1.0", "finding-explanation-deepseek-0.1.0",
            "f-synthetic", "rule", "0.1.0", "high", "message", "logical-entity",
            List.of(), List.of(new AgentModelPort.DocumentInput("d-synthetic", "a".repeat(64), excerpt)), null);
    }
}
