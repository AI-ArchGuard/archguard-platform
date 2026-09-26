package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class GovernanceReadContractTest {
    @Test void readContractReferencesResolveAndScopeIsExplicit() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("openapi", "governance-read-v1.json").toFile());
        assertThat(root.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(root.path("x-archguard-lifecycle").asText()).isEqualTo("runtime-3f");
        assertThat(root.path("paths").size()).isEqualTo(3);
        List<String> refs = new ArrayList<>();
        collect(root, refs);
        for (String ref : refs) {
            assertThat(ref).startsWith("#/components/");
            assertThat(root.at(ref.substring(1)).isMissingNode()).isFalse();
        }
        assertThat(StreamSupport.stream(root.at("/components/schemas/GateEvaluation/properties/outcome/enum")
            .spliterator(), false).map(JsonNode::asText).toList()).containsExactly("PASS", "FAIL", "ERROR");
        assertThat(StreamSupport.stream(root.at("/components/schemas/ClassifiedFinding/properties/classification/enum")
            .spliterator(), false).map(JsonNode::asText).toList()).containsExactly("NEW", "EXISTING", "RESOLVED");
    }
    private static void collect(JsonNode node, List<String> refs) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if ("$ref".equals(entry.getKey())) refs.add(entry.getValue().asText());
            else collect(entry.getValue(), refs);
        });
        else if (node.isArray()) node.forEach(value -> collect(value, refs));
    }
}
