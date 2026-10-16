package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentLiveContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void liveApiCannotAcceptCredentialsInputModelOrCallerPolicyAndReusesValidatedResults() throws Exception {
        var file = Path.of("openapi/agent-live-v1.json");
        var api = mapper.readTree(file.toFile());
        assertThat(api.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(api.at("/info/version").asText()).isEqualTo("0.1.0");
        assertThat(api.path("paths").size()).isOne();
        assertThat(api.path("x-archguard-default-enabled").isBoolean()).isTrue();
        assertThat(api.path("x-archguard-default-enabled").asBoolean()).isFalse();
        assertThat(api.path("x-archguard-request-body-max-bytes").asInt()).isEqualTo(8192);
        JsonNode input = api.at("/components/schemas/LiveAgentRunInput");
        assertThat(input.path("properties").size()).isEqualTo(2);
        assertThat(input.path("additionalProperties").asBoolean()).isFalse();
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(new java.io.ByteArrayInputStream(mapper.writeValueAsBytes(input)));
        var body = mapper.createObjectNode().put("batchId", "11111111-1111-4111-8111-111111111111")
            .put("templateRequestId", "22222222-2222-4222-8222-222222222222");
        assertThat(schema.validate(body.toString(), InputFormat.JSON)).isEmpty();
        for (String field : List.of("apiKey", "model", "endpoint", "input", "actor", "costPolicyVersion", "forceRetry")) {
            body.put(field, "forbidden");
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
            body.remove(field);
        }
        for (String field : List.of("batchId", "templateRequestId")) {
            var invalid = body.deepCopy(); invalid.put(field, "1-1-1-1-1");
            assertThat(schema.validate(invalid.toString(), InputFormat.JSON)).isNotEmpty();
            invalid.putNull(field);
            assertThat(schema.validate(invalid.toString(), InputFormat.JSON)).isNotEmpty();
        }
        resolve(api, api, file);
    }
    private void resolve(JsonNode value, JsonNode root, Path file) throws Exception {
        if (value.isObject() && value.has("$ref")) {
            String reference = value.path("$ref").asText();
            String[] parts = reference.split("#", 2);
            var target = parts[0].isEmpty() ? root : mapper.readTree(file.getParent().resolve(parts[0]).normalize().toFile());
            assertThat(target.at(parts[1]).isMissingNode()).isFalse();
        }
        if (value.isContainerNode()) for (JsonNode child : value) resolve(child, root, file);
    }
}
