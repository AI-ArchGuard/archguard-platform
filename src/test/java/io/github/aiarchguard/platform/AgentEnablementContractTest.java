package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentEnablementContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void acknowledgementSchemaMatchesRuntimeAndRejectsUntrustedFields() throws Exception {
        var path = Path.of("contracts/agent-personal-enablement-0.2.0.schema.json");
        try (var stream = Files.newInputStream(path)) {
            var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
            var fixture = new AgentPersonalEnablementIntegrationTest();
            ObjectNode body = mapper.valueToTree(fixture.input());
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isEmpty();
            body.put("apiKey", "synthetic-forbidden-key");
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
            body.remove("apiKey");
            body.put("accountRef", "sk-synthetic-not-a-key");
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
            body.put("accountRef", "synthetic-account");
            body.put("riskAccepted", false);
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
            body.put("riskAccepted", true);
            body.put("approvedBy", "synthetic-self-reported-actor");
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
        }
    }

    @Test void additiveApiHasLockedSettingsNoKeyReadAndResolvableReferences() throws Exception {
        var file = Path.of("openapi/agent-enablement-v1.json");
        JsonNode api = mapper.readTree(file.toFile());
        assertThat(api.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(api.path("info").path("version").asText()).isEqualTo("0.1.0");
        assertThat(api.path("x-archguard-request-body-max-bytes").asInt()).isEqualTo(8192);
        assertThat(api.path("paths").size()).isEqualTo(4);
        assertThat(api.at("/components/schemas/Settings/properties/enabled/default").asBoolean()).isFalse();
        assertThat(api.at("/components/schemas/Settings/properties/modelCallsAvailable/const").asBoolean()).isFalse();
        assertThat(api.at("/components/schemas/Enablement/properties").toString()).doesNotContain("apiKey", "accountRef", "networkCheckRef", "secretCheckRef", "sources");
        assertThat(api.path("paths").path("/api/v1/projects/{projectId}/agent/settings").path("put").path("responses").has("428")).isTrue();
        assertThat(api.path("paths").path("/api/v1/projects/{projectId}/agent/settings").path("put").path("responses").has("412")).isTrue();
        resolve(api, api, file);
        var schemaFile = Path.of("contracts/agent-personal-enablement-0.2.0.schema.json");
        JsonNode body = mapper.readTree(schemaFile.toFile());
        resolve(body, body, schemaFile);
    }
    private void resolve(JsonNode value, JsonNode root, Path file) throws Exception {
        if (value.isObject()) {
            if (value.has("$ref")) {
                String ref = value.path("$ref").asText();
                if (ref.startsWith("#/")) assertThat(root.at(ref.substring(1)).isMissingNode()).isFalse();
                else {
                    assertThat(ref).isEqualTo("../contracts/agent-personal-enablement-0.2.0.schema.json");
                    assertThat(Files.isRegularFile(file.getParent().resolve(ref).normalize())).isTrue();
                }
            }
            var fields = value.elements();
            while (fields.hasNext()) resolve(fields.next(), root, file);
        } else if (value.isArray()) for (JsonNode child : value) resolve(child, root, file);
    }
}
