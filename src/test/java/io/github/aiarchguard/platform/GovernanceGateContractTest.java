package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class GovernanceGateContractTest {
    @Test void runtimeContractHasResolvableReferencesAndFixedExitCodes() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("openapi", "governance-gates-v1.json").toFile());
        assertThat(root.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(root.path("x-archguard-lifecycle").asText()).isEqualTo("runtime-3d");
        assertThat(root.path("paths").size()).isEqualTo(5);
        List<String> refs = new ArrayList<>();
        collect(root, refs);
        for (String ref : refs) {
            assertThat(ref).startsWith("#/components/");
            assertThat(root.at(ref.substring(1)).isMissingNode()).isFalse();
        }
        assertThat(StreamSupport.stream(root.at("/components/schemas/GateEvaluation/properties/outcome/enum")
            .spliterator(), false).map(JsonNode::asText).toList()).containsExactly("PASS", "FAIL", "ERROR");
        assertThat(StreamSupport.stream(root.at("/components/schemas/GateEvaluation/properties/ciExitCode/enum")
            .spliterator(), false).map(JsonNode::asInt).toList()).containsExactly(0, 2, 64, 70);
    }
    private static void collect(JsonNode node, List<String> refs) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if ("$ref".equals(entry.getKey())) refs.add(entry.getValue().asText());
            else collect(entry.getValue(), refs);
        });
        else if (node.isArray()) node.forEach(value -> collect(value, refs));
    }
}
