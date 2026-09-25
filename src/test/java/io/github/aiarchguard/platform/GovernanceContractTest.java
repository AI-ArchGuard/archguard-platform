package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class GovernanceContractTest {
    private static final Path CONTRACT = Path.of("openapi", "governance-v1.json");

    @Test
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
