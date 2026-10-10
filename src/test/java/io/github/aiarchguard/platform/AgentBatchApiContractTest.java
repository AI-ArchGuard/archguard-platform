package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.aiarchguard.platform.agent.LiveBatchInput;
import io.github.aiarchguard.platform.agent.LiveBatchPreviewInput;
import io.github.aiarchguard.platform.agent.LiveBatchPreviewView;
import io.github.aiarchguard.platform.agent.LiveBatchView;
import java.io.ByteArrayInputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentBatchApiContractTest {
    private static final String PREFIX = "/api/v1/projects/{projectId}/agent/batches";
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String HASH = "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void previewInputOnlyAcceptsUniqueBoundedTemplateIds() throws Exception {
        Schema schema = fileSchema("contracts/agent-batch-preview-0.1.0.schema.json");
        JsonNode definition = mapper.readTree(Path.of("contracts/agent-batch-preview-0.1.0.schema.json").toFile());
        assertThat(names(definition.path("properties"))).containsExactlyInAnyOrder(recordNames(LiveBatchPreviewInput.class));
        assertThat(strings(definition.path("required"))).containsExactlyInAnyOrder(recordNames(LiveBatchPreviewInput.class));
        ObjectNode valid = mapper.createObjectNode();
        valid.putArray("templateRequestIds").add(ID);
        valid(schema, valid);
        for (String field : List.of("apiKey", "approvedBy", "projectId", "manifestSha256", "input", "endpoint")) {
            invalid(schema, valid.deepCopy().put(field, "synthetic-forbidden-field"));
        }
        invalid(schema, mapper.createObjectNode());
        invalid(schema, mapper.createObjectNode().put("templateRequestIds", ID));
        ObjectNode empty = mapper.createObjectNode(); empty.putArray("templateRequestIds"); invalid(schema, empty);
        ObjectNode duplicate = valid.deepCopy(); ((ArrayNode) duplicate.get("templateRequestIds")).add(ID); invalid(schema, duplicate);
        ObjectNode oversized = mapper.createObjectNode(); ArrayNode ids = oversized.putArray("templateRequestIds");
        for (int index = 0; index < 21; index++) ids.add(new UUID(0, index + 1).toString());
        invalid(schema, oversized);
    }

    @Test void approvalRetainsEightFieldContractAndRequiresBoundedConsent() throws Exception {
        JsonNode definition = mapper.readTree(Path.of("contracts/agent-live-batch-0.1.0.schema.json").toFile());
        Schema schema = fileSchema("contracts/agent-live-batch-0.1.0.schema.json");
        ObjectNode valid = approval(); valid(schema, valid);
        assertThat(names(definition.path("properties"))).containsExactlyInAnyOrder(recordNames(LiveBatchInput.class));
        assertThat(strings(definition.path("required"))).containsExactlyInAnyOrder(recordNames(LiveBatchInput.class));
        for (String field : List.of("approvedBy", "approvedAt", "apiKey", "endpoint", "inventoryProof", "priceCatalogVersion")) {
            invalid(schema, valid.deepCopy().put(field, "synthetic-forbidden-field"));
        }
        invalid(schema, valid.deepCopy().put("callsApproved", false));
        invalid(schema, valid.deepCopy().put("callsApproved", "true"));
        for (int count : new int[] {0, 21}) invalid(schema, valid.deepCopy().put("maxRequests", count));
        for (long cost : new long[] {4199, 84001}) invalid(schema, valid.deepCopy().put("maxCostMicrousd", cost));
        invalid(schema, valid.deepCopy().put("maxRequests", 1.5));
        invalid(schema, valid.deepCopy().put("manifestSha256", "not-a-digest"));
        for (String field : recordNames(LiveBatchInput.class)) {
            ObjectNode missing = valid.deepCopy(); missing.remove(field); invalid(schema, missing);
        }
    }

    @Test void inventoryIsStrictBoundedOperatorReviewWithoutSecretsOrExternalFetches() throws Exception {
        JsonNode definition = mapper.readTree(Path.of("contracts/agent-synthetic-inventory-0.1.0.schema.json").toFile());
        Schema schema = fileSchema("contracts/agent-synthetic-inventory-0.1.0.schema.json");
        ObjectNode valid = inventory(); valid(schema, valid);
        assertThat(definition.path("x-archguard-file-max-bytes").asInt()).isEqualTo(32768);
        assertThat(definition.path("x-archguard-entry-max-lifetime-seconds").asInt()).isEqualTo(86400);
        assertThat(names(definition.path("properties"))).containsExactlyInAnyOrder("schemaVersion", "deploymentId", "entries");
        assertThat(names(definition.at("/$defs/Entry/properties"))).containsExactlyInAnyOrder(
            "inventoryId", "projectId", "manifestSha256", "sourceKind", "fixtureSetVersion", "fixtureArtifactSha256", "reviewRef", "reviewedAt", "expiresAt");
        invalid(schema, valid.deepCopy().put("apiKey", "synthetic-forbidden-field"));
        invalid(schema, valid.deepCopy().put("schemaVersion", "0.2.0"));
        invalid(schema, valid.deepCopy().put("deploymentId", "shortened-uuid"));
        invalid(schema, valid.deepCopy().put("deploymentId", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"));
        for (String field : List.of("apiKey", "url", "path", "input", "approvedBy", "callsApproved")) {
            ObjectNode body = valid.deepCopy(); firstEntry(body).put(field, "synthetic-forbidden-field"); invalid(schema, body);
        }
        for (String reference : List.of("sk-synthetic", "SK-synthetic", "ghp_synthetic", "GHP_synthetic", "github_pat_synthetic", "GitHub_PAT_synthetic", "https://fixture.example/review", "C:/fixture", "a".repeat(129), "contains space", "")) {
            for (String field : List.of("fixtureSetVersion", "reviewRef")) {
                ObjectNode body = valid.deepCopy(); firstEntry(body).put(field, reference); invalid(schema, body);
            }
        }
        for (String field : List.of("manifestSha256", "fixtureArtifactSha256")) {
            ObjectNode body = valid.deepCopy(); firstEntry(body).put(field, "A".repeat(64)); invalid(schema, body);
        }
        for (String field : List.of("inventoryId", "projectId")) {
            ObjectNode body = valid.deepCopy(); firstEntry(body).put(field, "1-1-1-1-1"); invalid(schema, body);
        }
        ObjectNode wrongSource = valid.deepCopy(); firstEntry(wrongSource).put("sourceKind", "USER_ASSERTED_SYNTHETIC"); invalid(schema, wrongSource);
        ObjectNode noEntries = valid.deepCopy(); noEntries.putArray("entries"); invalid(schema, noEntries);
        ObjectNode duplicate = valid.deepCopy(); ((ArrayNode) duplicate.get("entries")).add(firstEntry(valid).deepCopy()); invalid(schema, duplicate);
        ObjectNode tooMany = valid.deepCopy(); ArrayNode entries = tooMany.putArray("entries");
        for (int index = 0; index < 21; index++) entries.add(firstEntry(valid).deepCopy().put("inventoryId", new UUID(0, index + 1).toString()));
        invalid(schema, tooMany);
        resolve(definition, definition, Path.of("contracts/agent-synthetic-inventory-0.1.0.schema.json"));
    }

    @Test void apiExposesOnlyMetadataManagementAndAllResponsesAreNoStore() throws Exception {
        Path file = Path.of("openapi/agent-batches-v1.json"); JsonNode api = mapper.readTree(file.toFile());
        assertThat(api.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(api.at("/info/version").asText()).isEqualTo("0.1.0");
        assertThat(api.path("x-archguard-request-body-max-bytes").asInt()).isEqualTo(8192);
        assertThat(api.path("x-archguard-strict-json").asBoolean()).isTrue();
        assertThat(api.path("x-archguard-model-calls").asBoolean()).isFalse();
        assertThat(api.path("x-archguard-cache-control").asText()).isEqualTo("no-store");
        assertThat(names(api.path("paths"))).containsExactlyInAnyOrder(PREFIX + "/preview", PREFIX, PREFIX + "/{batchId}", PREFIX + "/{batchId}/revoke");
        for (String route : names(api.path("paths"))) {
            JsonNode item = api.path("paths").path(route);
            assertThat(names(item)).containsExactlyInAnyOrder("parameters", route.equals(PREFIX + "/{batchId}") ? "get" : "post");
            JsonNode operation = item.path(route.equals(PREFIX + "/{batchId}") ? "get" : "post");
            assertThat(operation.path("responses").has("401")).isTrue();
            if (!route.equals(PREFIX + "/{batchId}")) {
                assertThat(operation.at("/parameters/0/$ref").asText()).isEqualTo("#/components/parameters/Origin");
            }
            for (JsonNode response : operation.path("responses")) {
                JsonNode resolved = dereference(response, api);
                JsonNode header = dereference(resolved.at("/headers/Cache-Control"), api);
                assertThat(header.at("/schema/const").asText()).isEqualTo("no-store");
            }
        }
        JsonNode preview = api.path("paths").path(PREFIX + "/preview").path("post");
        assertThat(preview.path("x-archguard-read-only").asBoolean()).isTrue();
        assertThat(preview.at("/requestBody/content/application~1json/schema/$ref").asText()).isEqualTo("../contracts/agent-batch-preview-0.1.0.schema.json");
        JsonNode revoke = api.path("paths").path(PREFIX + "/{batchId}/revoke").path("post");
        assertThat(revoke.path("x-archguard-empty-body").asBoolean()).isTrue();
        assertThat(revoke.has("requestBody")).isFalse();
        assertThat(api.path("paths").toString()).doesNotContain("/reserve", "/settle", "/send", "/requestDigest", "/inventory", "/credentials");
        resolve(api, api, file);
    }

    @Test void returnedMetadataMatchesExistingDtoAndDoesNotExposeProofOrInputs() throws Exception {
        JsonNode api = mapper.readTree(Path.of("openapi/agent-batches-v1.json").toFile());
        JsonNode batchSchema = api.at("/components/schemas/Batch");
        assertThat(names(batchSchema.path("properties"))).containsExactlyInAnyOrder(recordNames(LiveBatchView.class));
        assertThat(strings(batchSchema.path("required"))).containsExactlyInAnyOrder(recordNames(LiveBatchView.class));
        assertThat(batchSchema.path("properties").toString()).doesNotContain("apiKey", "credentialVersion", "inventoryProof", "templateRequestIds", "input", "candidates", "fixtureArtifactSha256");
        UUID id = UUID.fromString(ID);
        JsonNode body = mapper.valueToTree(new LiveBatchView(id, id, id, id, Instant.parse("2026-10-10T00:00:00Z"), HASH,
            Instant.parse("2026-10-10T01:00:00Z"), 1, 4200, false));
        assertThat(body.path("approvedAt").isTextual()).isTrue();
        assertThat(body.path("expiresAt").isTextual()).isTrue();
        valid(nodeSchema(batchSchema), body);
        JsonNode previewSchema = api.at("/components/schemas/Preview");
        assertThat(names(previewSchema.path("properties"))).containsExactlyInAnyOrder("schemaVersion", "manifestSha256", "templateCount");
        assertThat(names(previewSchema.path("properties"))).containsExactlyInAnyOrder(recordNames(LiveBatchPreviewView.class));
        assertThat(strings(previewSchema.path("required"))).containsExactlyInAnyOrder(recordNames(LiveBatchPreviewView.class));
        ObjectNode preview = mapper.createObjectNode().put("schemaVersion", "0.1.0").put("manifestSha256", HASH).put("templateCount", 1);
        valid(nodeSchema(previewSchema), preview);
        invalid(nodeSchema(previewSchema), preview.deepCopy().put("input", "synthetic-forbidden-field"));
        invalid(nodeSchema(previewSchema), preview.deepCopy().put("templateCount", 0));
    }

    private ObjectNode approval() {
        ObjectNode body = mapper.createObjectNode().put("enablementId", ID).put("syntheticInventoryId", ID)
            .put("manifestSha256", HASH).put("expiresAt", "2026-10-10T01:00:00Z").put("maxRequests", 1)
            .put("maxCostMicrousd", 4200).put("callsApproved", true);
        body.putArray("templateRequestIds").add(ID); return body;
    }
    private ObjectNode inventory() {
        ObjectNode body = mapper.createObjectNode().put("schemaVersion", "0.1.0").put("deploymentId", ID);
        body.putArray("entries").addObject().put("inventoryId", ID).put("projectId", ID).put("manifestSha256", HASH)
            .put("sourceKind", "CONTROLLED_SYNTHETIC_FIXTURES").put("fixtureSetVersion", "synthetic-fixtures-0.1.0")
            .put("fixtureArtifactSha256", "b".repeat(64)).put("reviewRef", "synthetic-review-001")
            .put("reviewedAt", "2026-10-10T00:00:00Z").put("expiresAt", "2026-10-10T01:00:00Z");
        return body;
    }
    private ObjectNode firstEntry(ObjectNode body) { return (ObjectNode) body.path("entries").get(0); }
    private Schema fileSchema(String file) throws Exception {
        try (var stream = Files.newInputStream(Path.of(file))) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
        }
    }
    private Schema nodeSchema(JsonNode value) throws Exception {
        try (var stream = new ByteArrayInputStream(mapper.writeValueAsBytes(value))) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
        }
    }
    private void valid(Schema schema, JsonNode body) { assertThat(schema.validate(body.toString(), InputFormat.JSON)).isEmpty(); }
    private void invalid(Schema schema, JsonNode body) { assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty(); }
    private List<String> names(JsonNode node) { List<String> values = new ArrayList<>(); node.fieldNames().forEachRemaining(values::add); return values; }
    private List<String> strings(JsonNode node) { List<String> values = new ArrayList<>(); node.forEach(value -> values.add(value.asText())); return values; }
    private String[] recordNames(Class<?> type) { return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new); }
    private JsonNode dereference(JsonNode node, JsonNode root) { return node.has("$ref") ? root.at(node.path("$ref").asText().substring(1)) : node; }
    private void resolve(JsonNode value, JsonNode root, Path file) throws Exception {
        if (value.isObject()) {
            if (value.has("$ref")) {
                String ref = value.path("$ref").asText();
                if (ref.startsWith("#/")) assertThat(root.at(ref.substring(1)).isMissingNode()).isFalse();
                else {
                    assertThat(ref).isIn("../contracts/agent-batch-preview-0.1.0.schema.json", "../contracts/agent-live-batch-0.1.0.schema.json");
                    assertThat(Files.isRegularFile(file.getParent().resolve(ref).normalize())).isTrue();
                }
            }
            for (JsonNode child : value) resolve(child, root, file);
        } else if (value.isArray()) for (JsonNode child : value) resolve(child, root, file);
    }
}
