package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * 离线核对治理与 GitHub 适配器契约的版本、接口范围及引用，保持门禁结果枚举固定。Webhook 的传输边界描述不代替运行时签名校验。
 */
class GovernanceContractTest {
    private static final Path CONTRACT = Path.of("openapi", "governance-v1.json");

    @Test
    // 固定 Webhook 契约入口和认证描述，实际真实性仍由运行时 HMAC 校验负责。
    void githubAdapterContractHasResolvableReferencesAndUnsignedWebhookBoundary() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("openapi", "governance-github-v1.json").toFile());
        assertThat(root.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(root.path("paths").size()).isEqualTo(3);
        assertThat(root.at("/paths/~1api~1v1~1github~1webhooks/post/security").size()).isZero();
        assertThat(root.at("/paths/~1api~1v1~1github~1webhooks/post/parameters").size()).isEqualTo(3);
        List<String> references = new ArrayList<>();
        collectReferences(root, references);
        for (String reference : references) {
            assertThat(reference).startsWith("#/components/");
            assertThat(root.at(reference.substring(1)).isMissingNode()).isFalse();
        }
    }

    @Test
    // 合同中的门禁枚举与路径不应因解释或适配器增量漂移。
    void contractHasResolvableReferencesAndFrozenGateResults() throws Exception {
        JsonNode root = new ObjectMapper().readTree(CONTRACT.toFile());
        assertThat(root.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(root.path("x-archguard-lifecycle").asText()).isEqualTo("runtime-3e");
        assertThat(root.path("paths").size()).isEqualTo(3);

        List<String> references = new ArrayList<>();
        collectReferences(root, references);
        assertThat(references).isNotEmpty();
        for (String reference : references) {
            assertThat(reference).startsWith("#/components/");
            assertThat(root.at(reference.substring(1)).isMissingNode()).isFalse();
        }

        JsonNode gate = root.at("/components/schemas/GateEvaluation");
        assertThat(StreamSupport.stream(gate.path("properties").path("outcome").path("enum").spliterator(), false)
                .map(JsonNode::asText).toList()).containsExactly("PASS", "FAIL", "ERROR");
        assertThat(StreamSupport.stream(gate.path("properties").path("ciExitCode").path("enum").spliterator(), false)
                .map(JsonNode::asInt).toList()).containsExactly(0, 2, 64, 70);
        assertThat(gate.path("properties").path("fingerprintVersion").path("const").asText())
                .isEqualTo("platform-finding-v1");
    }

    // 递归检查本地组件引用是否存在，不发起远程解析。
    private static void collectReferences(JsonNode node, List<String> references) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getKey().equals("$ref")) {
                    references.add(entry.getValue().asText());
                } else {
                    collectReferences(entry.getValue(), references);
                }
            });
        } else if (node.isArray()) {
            node.forEach(value -> collectReferences(value, references));
        }
    }
}
