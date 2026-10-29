package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 验证四个获批根元数据字段仅接受惰性形式，不扩大嵌套或业务白名单。参数化输入检查结果、用量与固定拒绝原因。
 */
class DeepSeekResponseMetadataTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> MODELS = Set.of("deepseek-flash");
    private static final List<String> POLICIES = List.of(LiveCostPolicy.VERSION, LiveCostPolicy.CONTEXT_VERSION);
    private static final List<String> FIELDS = List.of("user", "max_tool_calls", "top_logprobs", "completed_at");
    // Independent synthetic wire fixture: no production builder or captured provider response.
    private static final String BASE = """
        {"id":"resp-metadata-synthetic","object":"response","status":"completed","model":"deepseek-flash",
         "output":[{"id":"msg-synthetic","type":"message","role":"assistant","status":"completed",
           "content":[{"type":"output_text","text":"{\\"unverified\\":true}","annotations":[],"logprobs":[]}]}],
         "usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120,
           "input_tokens_details":{"cached_tokens":40},"output_tokens_details":{"reasoning_tokens":0}}}
        """;
    private final DeepSeekResponsesCodec codec = new DeepSeekResponsesCodec(MAPPER);

    @ParameterizedTest(name = "accepted metadata: {0}")
    @MethodSource("permittedMetadata")
    // 获批元数据不能改变业务文本或可信用量。
    void acceptsOnlyApprovedInertFormsWithoutChangingUsageOrBusinessText(String metadata) {
        for (String policy : POLICIES) {
            var decoded = assertDoesNotThrow(() -> codec.decode(wire(metadata), MODELS, policy));
            assertThat(decoded.rawJson()).isEqualTo("{\"unverified\":true}");
            assertThat(decoded.inputTokens()).isEqualTo(100);
            assertThat(decoded.outputTokens()).isEqualTo(20);
            assertThat(decoded.cachedInputTokens()).isEqualTo(40);
            assertThat(decoded.responseId()).isEqualTo("resp-metadata-synthetic");
            assertThat(decoded.actualModelId()).isEqualTo("deepseek-flash");
            assertThat(decoded.toString()).doesNotContain("unverified", "user", "max_tool_calls", "top_logprobs", "completed_at");
        }
    }

    @ParameterizedTest(name = "rejected metadata: {0}")
    @MethodSource("forbiddenMetadata")
    // 错类型或有行为含义的值先使外层失效，不能先接受用量。
    void rejectsForbiddenMetadataBeforeAcceptingUsage(String name, String metadata) {
        for (String policy : POLICIES) assertRejected(wire(metadata), policy, "ENVELOPE_METADATA");
    }

    @ParameterizedTest(name = "duplicate root metadata: {0}")
    @MethodSource("approvedFieldNames")
    void stillRejectsDuplicateApprovedFields(String field) {
        byte[] bytes = wire("\"" + field + "\":null,\"" + field + "\":null");
        for (String policy : POLICIES) assertRejected(bytes, policy, "RESPONSE_JSON");
    }

    @ParameterizedTest(name = "unapproved root name: {0}")
    @MethodSource("unknownFieldNames")
    // 出现获批元数据不意味着其他根字段可以被接受。
    void approvedMetadataCannotHideAnyUnknownRootField(String field) {
        byte[] bytes = wire("\"user\":null,\"max_tool_calls\":null,\"top_logprobs\":0,"
            + "\"created_at\":1,\"completed_at\":2,\"" + field + "\":null");
        for (String policy : POLICIES) assertRejected(bytes, policy, "ENVELOPE_UNKNOWN_FIELDS");
    }

    @ParameterizedTest(name = "root-only field {0} in {1}")
    @MethodSource("nestedMetadata")
    // 根字段兼容仅限根层，嵌套对象继续按原白名单校验。
    void rootCompatibilityDoesNotBroadenAnyNestedAllowlist(String field, String pointer, String reason) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(wire("\"user\":null,\"max_tool_calls\":null,"
            + "\"top_logprobs\":0,\"created_at\":1,\"completed_at\":2"));
        ((ObjectNode) root.at(pointer)).putNull(field);
        for (String policy : POLICIES) assertRejected(MAPPER.writeValueAsBytes(root), policy, reason);
    }

    private static Stream<String> permittedMetadata() {
        return Stream.of("", "\"user\":null", "\"max_tool_calls\":null", "\"top_logprobs\":null",
            "\"top_logprobs\":0", "\"completed_at\":null",
            "\"user\":null,\"max_tool_calls\":null,\"top_logprobs\":null,\"completed_at\":null",
            "\"user\":null,\"max_tool_calls\":null,\"top_logprobs\":0,\"created_at\":1,\"completed_at\":2",
            "\"created_at\":0,\"completed_at\":0", "\"created_at\":1,\"completed_at\":1",
            "\"created_at\":1741386163,\"completed_at\":1741386164",
            "\"created_at\":253402300799,\"completed_at\":253402300799",
            // Do not silently add created_at validation when completion is absent/null.
            "\"created_at\":\"legacy-unvalidated\"", "\"created_at\":null,\"completed_at\":null",
            "\"created_at\":-1,\"completed_at\":null");
    }

    // 参数集覆盖非惰性值、错误类型与时间边界。
    private static Stream<Arguments> forbiddenMetadata() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("user", "max_tool_calls")) {
            for (String value : List.of("\"private-metadata-value\"", "\"\"", "0", "false", "{}", "[]", "1.5")) {
                cases.add(arguments(field + " non-null " + value, "\"" + field + "\":" + value));
            }
        }
        for (String value : List.of("-1", "1", "20", "21", "2147483648", "9223372036854775808",
                "0.0", "0e0", "\"0\"", "false", "{}", "[]")) {
            cases.add(arguments("nonzero or nonintegral top_logprobs " + value, "\"top_logprobs\":" + value));
        }
        List<String> invalidTimes = List.of("-1", "253402300800", "9223372036854775807", "9223372036854775808",
            "0.5", "1.0", "1e0", "1e1000", "\"1\"", "false", "{}", "[]");
        for (String value : invalidTimes) {
            cases.add(arguments("invalid completion " + value, "\"created_at\":0,\"completed_at\":" + value));
            cases.add(arguments("invalid creation " + value, "\"created_at\":" + value + ",\"completed_at\":253402300799"));
        }
        cases.add(arguments("missing creation", "\"completed_at\":0"));
        cases.add(arguments("null creation", "\"created_at\":null,\"completed_at\":0"));
        cases.add(arguments("completion precedes creation", "\"created_at\":2,\"completed_at\":1"));
        return cases.stream();
    }

    private static Stream<String> approvedFieldNames() { return FIELDS.stream(); }

    private static Stream<String> unknownFieldNames() {
        return Stream.of("private-metadata-field", "access_programs", "conversation", "prompt", "prompt_cache_key",
            "prompt_cache_retention", "prompt_cache_options", "prompt_cache_diagnostics", "moderation", "safety_identifier");
    }

    private static Stream<Arguments> nestedMetadata() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : FIELDS) {
            for (String pointer : List.of("/usage", "/usage/input_tokens_details", "/usage/output_tokens_details")) {
                cases.add(arguments(field, pointer, "ACCOUNTING_FIELDS"));
            }
            for (String pointer : List.of("/output/0", "/output/0/content/0")) {
                cases.add(arguments(field, pointer, "OUTPUT_STRUCTURE"));
            }
        }
        return cases.stream();
    }

    private static byte[] wire(String metadata) {
        return ("{" + metadata + (metadata.isEmpty() ? "" : ",") + BASE.substring(1)).getBytes(StandardCharsets.UTF_8);
    }

    private void assertRejected(byte[] bytes, String policy, String reason) {
        assertThatThrownBy(() -> codec.decode(bytes, MODELS, policy))
            .isInstanceOf(DeepSeekResponseRejection.class).hasMessage("Invalid DeepSeek wire payload").hasNoCause()
            .satisfies(failure -> assertThat(((DeepSeekResponseRejection) failure).reason().name()).isEqualTo(reason));
    }
}
