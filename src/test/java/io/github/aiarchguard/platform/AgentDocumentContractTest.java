package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentDocumentContractTest {
    @Test
    void documentOpenApiIsVersionedBoundedAndHasResolvableReferences() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("openapi", "agent-documents-v1.json").toFile());
        assertThat(root.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(root.path("info").path("version").asText()).isEqualTo("0.1.0");
        assertThat(root.path("paths").size()).isEqualTo(4);
        assertThat(root.path("paths").toString()).doesNotContain("agent/requests", "gate-evaluations", "baselines");
        assertThat(root.at("/components/schemas/SearchRequest/properties/versionIds/maxItems").asInt()).isEqualTo(10);
        assertThat(root.at("/components/schemas/SearchResponse/properties/items/maxItems").asInt()).isEqualTo(4);
        List<String> references = new ArrayList<>();
        collect(root, references);
        for (String reference : references) {
            assertThat(reference).startsWith("#/components/");
            assertThat(root.at(reference.substring(1)).isMissingNode()).isFalse();
        }
    }

    private static void collect(JsonNode node, List<String> references) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if ("$ref".equals(entry.getKey())) references.add(entry.getValue().asText());
            else collect(entry.getValue(), references);
        });
        else if (node.isArray()) node.forEach(value -> collect(value, references));
    }
}
