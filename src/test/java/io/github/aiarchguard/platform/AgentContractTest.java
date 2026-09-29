package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class AgentContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Schema outputSchema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(getClass().getResourceAsStream("/contracts/agent-model-output-0.1.0.schema.json"));

    @Test
    void openApiDescribesFindingExplanationRuntimeAndAllReferencesResolve() throws Exception {
        JsonNode root = mapper.readTree(Path.of("openapi", "agent-v1.json").toFile());
        assertThat(root.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(root.path("x-archguard-lifecycle").asText()).isEqualTo("finding-explanation-4d");
        assertThat(root.path("paths").size()).isEqualTo(2);
        List<String> refs = new ArrayList<>();
        collect(root, refs);
        for (String ref : refs) {
            assertThat(ref).startsWith("#/components/");
            assertThat(root.at(ref.substring(1)).isMissingNode()).isFalse();
        }
        assertThat(values(root.at("/components/schemas/AgentRequest/properties/state/enum")))
            .containsExactly("QUEUED", "RUNNING", "SUCCEEDED", "FAILED");
        assertThat(values(root.at("/components/schemas/AgentResult/properties/evidenceCoverage/enum")))
            .containsExactly("NONE", "PARTIAL", "COMPLETE");
        assertThat(root.path("paths").toString()).doesNotContain("gate-evaluations", "baselines", "rule-sets");
    }

    @Test
    void strictModelSchemaAcceptsSupportedAndInsufficientOutputs() throws Exception {
        assertThat(errors(supported())).isEmpty();
        var insufficient = mapper.readTree(supported());
        ((com.fasterxml.jackson.databind.node.ObjectNode) insufficient).put("purpose", "PR_SUMMARY");
        var conclusion = (com.fasterxml.jackson.databind.node.ObjectNode) insufficient.path("conclusion");
        conclusion.put("kind", "INSUFFICIENT");
        conclusion.putNull("text");
        ((com.fasterxml.jackson.databind.node.ArrayNode) conclusion.path("citationIds")).removeAll();
        ((com.fasterxml.jackson.databind.node.ArrayNode) insufficient.path("claims")).removeAll();
        ((com.fasterxml.jackson.databind.node.ArrayNode) insufficient.path("ruleBasis")).removeAll();
        ((com.fasterxml.jackson.databind.node.ArrayNode) insufficient.path("suggestions")).removeAll();
        assertThat(errors(insufficient.toString())).isEmpty();
    }

    @Test
    void strictModelSchemaRejectsUnknownFieldsWrongVersionAndMissingFields() throws Exception {
        var extra = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(supported());
        extra.put("gateOutcome", "PASS");
        assertThat(errors(extra.toString())).isNotEmpty();
        var wrongVersion = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(supported());
        wrongVersion.put("schemaVersion", "9.9.9");
        assertThat(errors(wrongVersion.toString())).isNotEmpty();
        var missing = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(supported());
        missing.remove("limitations");
        assertThat(errors(missing.toString())).isNotEmpty();
        var unsafe = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(supported());
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsafe.path("suggestions").get(0))
            .put("kind", "AUTO_EDIT");
        assertThat(errors(unsafe.toString())).isNotEmpty();
    }

    @Test
    void everyModelObjectIsStrictAndAllFieldsRequired() throws Exception {
        JsonNode schema = mapper.readTree(getClass().getResourceAsStream(
            "/contracts/agent-model-output-0.1.0.schema.json"));
        assertStrict(schema);
    }

    private void assertStrict(JsonNode node) {
        if (node.isObject()) {
            if ("object".equals(node.path("type").asText())) {
                assertThat(node.path("additionalProperties").asBoolean()).isFalse();
                List<String> propertyNames = new ArrayList<>();
                node.path("properties").fieldNames().forEachRemaining(propertyNames::add);
                assertThat(values(node.path("required"))).containsExactlyInAnyOrderElementsOf(propertyNames);
            }
            node.elements().forEachRemaining(this::assertStrict);
        } else if (node.isArray()) node.forEach(this::assertStrict);
    }

    private List<String> errors(String payload) {
        return outputSchema.validate(payload, InputFormat.JSON).stream().map(Object::toString).toList();
    }

    private static List<String> values(JsonNode values) {
        return StreamSupport.stream(values.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static void collect(JsonNode node, List<String> refs) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if ("$ref".equals(entry.getKey())) refs.add(entry.getValue().asText());
            else collect(entry.getValue(), refs);
        });
        else if (node.isArray()) node.forEach(value -> collect(value, refs));
    }

    private static String supported() {
        return """
            {
              "schemaVersion":"0.1.0",
              "purpose":"FINDING_EXPLANATION",
              "conclusion":{"kind":"SUPPORTED","text":"The dependency reaches an internal package.","citationIds":["c1"]},
              "claims":[{"findingRef":"f1","text":"The scan shows a dependency.","citationIds":["c1"]}],
              "ruleBasis":[{"findingRef":"f1","text":"The internal-module-access rule applies.","citationIds":["c1"]}],
              "suggestions":[{"kind":"LOW_RISK_DIRECTION","text":"Review a public API boundary.","findingRefs":["f1"],"citationIds":["c1"],"requiresHumanReview":true}],
              "limitations":["Advice only; no gate change."]
            }
            """;
    }
}
