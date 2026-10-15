package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeepSeekContextResponsesCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeepSeekResponsesCodec codec = new DeepSeekResponsesCodec(mapper);
    private final Set<String> models = Set.of("deepseek-flash");

    @Test void legacyDefaultAccountingAndDecodeStillRejectInputsAboveEightThousand() throws Exception {
        byte[] legacy = bytes(envelope(8000, 1500, 0));
        assertThat(codec.decodeAccounting(legacy, models).inputTokens()).isEqualTo(8000);
        assertThat(codec.decode(legacy, models).inputTokens()).isEqualTo(8000);
        assertThat(codec.decodeAccounting(legacy, models, LiveCostPolicy.VERSION).inputTokens()).isEqualTo(8000);
        byte[] larger = bytes(envelope(8001, 20, 0));
        assertInvalid(() -> codec.decodeAccounting(larger, models));
        assertInvalid(() -> codec.decode(larger, models));
        assertInvalid(() -> codec.decodeAccounting(larger, models, LiveCostPolicy.VERSION));
    }

    @Test void contextAccountingAcceptsBillableUsagePastTheObservationThresholdAndAtTheFullBound() throws Exception {
        for (int input : new int[] {8000, 8001, 1_048_576}) {
            byte[] bytes = bytes(envelope(input, 1500, input));
            var accounting = codec.decodeAccounting(bytes, models, LiveCostPolicy.CONTEXT_VERSION);
            assertThat(accounting.inputTokens()).isEqualTo(input);
            assertThat(accounting.outputTokens()).isEqualTo(1500);
            assertThat(accounting.cachedInputTokens()).isEqualTo(input);
            assertThat(accounting.responseId()).isEqualTo("resp-context-synthetic");
            var decoded = codec.decode(bytes, models, LiveCostPolicy.CONTEXT_VERSION);
            assertThat(decoded.inputTokens()).isEqualTo(input);
            assertThat(decoded.rawJson()).isEqualTo("{\"unverified\":true}");
            assertThat(decoded.toString()).doesNotContain("unverified");
        }
    }

    @Test void rejectedBusinessOutputCannotEraseValidContextAccounting() throws Exception {
        for (String raw : List.of("private-synthetic-invalid-json", "[1]", "null", "{} {}")) {
            ObjectNode root = envelope(8001, 20, 40);
            ((ObjectNode) root.at("/output/0/content/0")).put("text", raw);
            byte[] bytes = bytes(root);
            var accounting = codec.decodeAccounting(bytes, models, LiveCostPolicy.CONTEXT_VERSION);
            assertThat(accounting.inputTokens()).isEqualTo(8001);
            assertThat(accounting.outputTokens()).isEqualTo(20);
            assertThat(accounting.cachedInputTokens()).isEqualTo(40);
            assertThat(accounting.toString()).doesNotContain(raw);
            assertInvalid(() -> codec.decodeOutput(accounting));
            assertInvalid(() -> codec.decode(bytes, models, LiveCostPolicy.CONTEXT_VERSION));
        }
    }

    @Test void unknownCatalogCannotSilentlyDecodeUsingEitherKnownLimit() throws Exception {
        byte[] bytes = bytes(envelope(100, 20, 0));
        for (String unknown : new String[] {null, "", "unknown", "private-synthetic-price"}) {
            assertInvalid(() -> codec.decodeAccounting(bytes, models, unknown));
            assertInvalid(() -> codec.decode(bytes, models, unknown));
        }
    }

    @Test void contextPolicyStillRejectsOutOfRangeFractionalAndInconsistentUsage() throws Exception {
        for (long input : new long[] {-1, 1_048_577, Integer.MAX_VALUE, Long.MAX_VALUE}) {
            var root = envelope(100, 20, 0); ((ObjectNode) root.path("usage")).put("input_tokens", input);
            reject(root);
        }
        var fractional = envelope(100, 20, 0); ((ObjectNode) fractional.path("usage")).put("input_tokens", 8001.5); reject(fractional);
        var output = envelope(100, 20, 0); ((ObjectNode) output.path("usage")).put("output_tokens", 1501); reject(output);
        var total = envelope(8001, 20, 0); ((ObjectNode) total.path("usage")).put("total_tokens", 8020); reject(total);
        var overflow = envelope(8001, 20, 0); ((ObjectNode) overflow.path("usage")).put("total_tokens", Long.MAX_VALUE); reject(overflow);
        var cached = envelope(8001, 20, 0); ((ObjectNode) cached.at("/usage/input_tokens_details")).put("cached_tokens", 8002); reject(cached);
        var reasoning = envelope(8001, 20, 0); ((ObjectNode) reasoning.at("/usage/output_tokens_details")).put("reasoning_tokens", 1); reject(reasoning);
    }

    @Test void contextPolicyDoesNotRelaxEnvelopeModelStatusOrSizeValidation() throws Exception {
        reject(envelope(8001, 20, 0).put("model", "other-model"));
        reject(envelope(8001, 20, 0).put("status", "incomplete"));
        reject(envelope(8001, 20, 0).put("store", true));
        reject(envelope(8001, 20, 0).put("private-synthetic-field", "private-synthetic-value"));
        assertInvalid(() -> codec.decodeAccounting(new byte[65537], models, LiveCostPolicy.CONTEXT_VERSION));
        assertInvalid(() -> codec.decodeAccounting(bytes(envelope(8001, 20, 0)), Set.of(), LiveCostPolicy.CONTEXT_VERSION));
    }

    private ObjectNode envelope(int input, int output, int cached) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree("""
            {"id":"resp-context-synthetic","object":"response","status":"completed","model":"deepseek-flash",
             "output":[{"id":"msg-synthetic","type":"message","role":"assistant","status":"completed",
               "content":[{"type":"output_text","text":"{\\"unverified\\":true}","annotations":[]}]}],
             "usage":{"input_tokens":0,"output_tokens":0,"total_tokens":0,
               "input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}}
            """);
        ((ObjectNode) root.path("usage")).put("input_tokens", input).put("output_tokens", output).put("total_tokens", input + output);
        ((ObjectNode) root.at("/usage/input_tokens_details")).put("cached_tokens", cached);
        return root;
    }
    private byte[] bytes(ObjectNode root) throws Exception { return mapper.writeValueAsBytes(root); }
    private void reject(ObjectNode root) throws Exception {
        byte[] bytes = bytes(root); assertInvalid(() -> codec.decodeAccounting(bytes, models, LiveCostPolicy.CONTEXT_VERSION));
    }
    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid DeepSeek wire payload").hasNoCause();
    }
}
