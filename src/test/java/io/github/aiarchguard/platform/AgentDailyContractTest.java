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
import java.io.ByteArrayInputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Daily 独立机器契约的离线回归；不启动应用、数据库或提供方，也不证明业务授权及期限。 */
class AgentDailyContractTest {
    private static final String PREFIX = "/api/v1/projects/{projectId}/agent/daily";
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // 放宽未知字段、同意、结构类型或数组/数值边界会使这些真实 Schema 变异失败。
    @Test void schemasRejectCoercionUnknownFieldsAndMissingConsent() throws Exception {
        for (String kind : List.of("risk", "grant", "request")) {
            Path file = Path.of("contracts/agent-personal-daily-" + kind + "-0.1.0.schema.json");
            JsonNode definition = mapper.readTree(file.toFile());
            Schema schema = schema(definition);
            ObjectNode body = fixture(kind);
            valid(schema, body);
            assertThat(body.path("schemaVersion").asText()).isEqualTo("0.1.0");
            strict(definition);
            String type = "Daily" + Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + "Input";
            dto(definition, type);
            JsonNode roundTrip = mapper.valueToTree(mapper.treeToValue(body, dailyType(type)));
            // 比较实际 JSON 数字；valueToTree 的 LongNode 与解析 fixture 的 IntNode 不是 wire 差异。
            assertThat(mapper.readTree(roundTrip.toString())).isEqualTo(body);
            for (String field : List.of("sourceCode", "prompt", "url", "apiKey", "model", "approvedBy", "projectId")) {
                invalid(schema, body.deepCopy().put(field, "synthetic-forbidden-field"));
            }
            for (String field : names(body)) {
                ObjectNode missing = body.deepCopy(); missing.remove(field); invalid(schema, missing);
                invalid(schema, body.deepCopy().putNull(field));
            }
            invalid(schema, body.deepCopy().put("schemaVersion", "0.2.0"));
            List<String> consents = switch (kind) {
                case "risk" -> List.of("sourcesReviewed", "riskAccepted", "materialRightsAccepted");
                case "grant" -> List.of("callsApproved");
                default -> List.of("selectedMaterialConfirmed");
            };
            for (String field : consents) {
                invalid(schema, body.deepCopy().put(field, false));
                invalid(schema, body.deepCopy().put(field, "true"));
                invalid(schema, body.deepCopy().put(field, 1));
            }
            if (!kind.equals("request")) {
                for (String hash : List.of("A".repeat(64), "a".repeat(63), "not-a-hash")) {
                    invalid(schema, body.deepCopy().put("runtimeBindingDigest", hash));
                }
                invalid(schema, body.deepCopy().put("expiresAt", "not-an-instant"));
                invalid(schema, body.deepCopy().put("expiresAt", "2026-02-30T01:00:00Z"));
            }
            if (kind.equals("grant")) {
                for (String uuid : List.of("1-1-1-1-1", "not-a-uuid")) invalid(schema, body.deepCopy().put("riskAcknowledgementId", uuid));
                for (long count : new long[] {0, 2147483648L}) invalid(schema, body.deepCopy().put("maxAttempts", count));
                for (long cost : new long[] {316372, 751619276450001L}) invalid(schema, body.deepCopy().put("maxCostMicrousd", cost));
                invalid(schema, body.deepCopy().put("maxAttempts", "1"));
                invalid(schema, body.deepCopy().put("maxAttempts", 1.5));
                invalid(schema, body.deepCopy().put("maxCostMicrousd", "316373"));
                invalid(schema, body.deepCopy().put("maxCostMicrousd", 316373.5));
                for (ArrayNode purposes : List.of(mapper.createArrayNode(), mapper.createArrayNode().addNull(),
                        mapper.createArrayNode().add(""), mapper.createArrayNode().add("CHAT"),
                        mapper.createArrayNode().add("PR_SUMMARY").add("PR_SUMMARY"))) {
                    ObjectNode mutation = body.deepCopy(); mutation.set("allowedPurposes", purposes); invalid(schema, mutation);
                }
                valid(schema, body.deepCopy().put("maxAttempts", 2147483647).put("maxCostMicrousd", 751619276450000L));
                // Schema 只能给出独立数值边界；依赖 maxAttempts 的上限须由服务和数据库核验。
                valid(schema, body.deepCopy().put("maxAttempts", 1).put("maxCostMicrousd", 350001));
            }
            if (kind.equals("request")) {
                invalid(schema, body.deepCopy().put("grantId", "1-1-1-1-1"));
                ObjectNode selection = (ObjectNode) body.path("selection");
                dto(definition.at("/$defs/Selection"), "DailySelection");
                for (String field : names(selection)) {
                    ObjectNode mutation = body.deepCopy(); ((ObjectNode) mutation.path("selection")).remove(field); invalid(schema, mutation);
                }
                for (String field : List.of("sourceCode", "prompt", "url")) {
                    ObjectNode mutation = body.deepCopy(); ((ObjectNode) mutation.path("selection")).put(field, "synthetic"); invalid(schema, mutation);
                }
                for (String field : List.of("findingIds", "documentVersionIds")) {
                    for (String mutationKind : List.of("null-array", "null-item", "duplicate", "oversized", "invalid-uuid")) {
                        ObjectNode mutation = body.deepCopy(); ObjectNode selected = (ObjectNode) mutation.path("selection");
                        ArrayNode ids = selected.putArray(field);
                        switch (mutationKind) {
                            case "null-array" -> selected.putNull(field);
                            case "null-item" -> ids.addNull();
                            case "duplicate" -> ids.add(ID).add(ID);
                            case "invalid-uuid" -> ids.add("1-1-1-1-1");
                            default -> { for (int i = 0; i < (field.equals("findingIds") ? 21 : 11); i++) ids.add(new UUID(0, i + 1).toString()); }
                        }
                        invalid(schema, mutation);
                    }
                }
                for (String field : List.of("purpose", "scanJobId", "reportSha256")) {
                    ObjectNode mutation = body.deepCopy(); ((ObjectNode) mutation.path("selection")).put(field, ""); invalid(schema, mutation);
                }
                ObjectNode summary = body.deepCopy(); ObjectNode selected = (ObjectNode) summary.path("selection");
                selected.put("purpose", "PR_SUMMARY").put("prHeadRevisionId", ID);
                ArrayNode ids = selected.putArray("findingIds");
                for (int i = 0; i < 20; i++) ids.add(new UUID(0, i + 1).toString());
                valid(schema, summary);
                selected.putNull("prHeadRevisionId"); invalid(schema, summary);
                ObjectNode noFinding = body.deepCopy(); ((ObjectNode) noFinding.path("selection")).putArray("findingIds"); invalid(schema, noFinding);
                ObjectNode twoFindings = body.deepCopy(); ((ArrayNode) twoFindings.at("/selection/findingIds")).add(new UUID(0, 21).toString()); invalid(schema, twoFindings);
                ObjectNode withPr = body.deepCopy(); ((ObjectNode) withPr.path("selection")).put("prHeadRevisionId", ID); invalid(schema, withPr);
            }
            resolve(definition, definition, file, new HashSet<>());
        }
    }

    // 新旧契约分离，保持可恢复创建响应、严格 DTO、可信零费用语义及旧机器契约原字节。
    @Test void apiReferencesAndOldContractBytesStayStable() throws Exception {
        Path file = Path.of("openapi/agent-daily-v1.json"); JsonNode api = mapper.readTree(file.toFile());
        assertThat(api.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(api.at("/info/version").asText()).isEqualTo("0.1.0");
        assertThat(api.path("x-archguard-default-enabled")).isEqualTo(mapper.getNodeFactory().booleanNode(false));
        assertThat(api.path("x-archguard-strict-json").asBoolean()).isTrue();
        assertThat(api.path("x-archguard-request-body-max-bytes").asInt()).isEqualTo(8192);
        assertThat(names(api.path("paths"))).containsExactlyInAnyOrder(PREFIX + "/context", PREFIX + "/settings",
            PREFIX + "/risk-acknowledgements", PREFIX + "/risk-acknowledgements/{id}", PREFIX + "/risk-acknowledgements/{id}/revoke",
            PREFIX + "/grants", PREFIX + "/grants/{id}", PREFIX + "/grants/{id}/revoke", PREFIX + "/requests", PREFIX + "/requests/lookup", PREFIX + "/requests/{requestId}");
        resolve(api, api, file, new HashSet<>());
        strict(api.path("components").path("schemas"));
        for (String type : List.of("DailyContext", "DailyBindingSnapshot", "DailyEvidence", "DailySource", "DailyLimits",
                "DailySettings", "DailySettingsInput", "DailyRiskView", "DailyGrantView", "DailyStop", "DailyUsage",
                "DailyRequestView", "DailyAuthorization", "DailyExecution", "DailyRequestPage")) dto(api.at("/components/schemas/" + type), type);
        enumMatches(api.at("/components/schemas/DailySettings/properties/unavailableReason/oneOf/0/enum"), "DailyUnavailableReason");
        enumMatches(api.at("/components/schemas/DailyGrantView/properties/state/enum"), "DailyGrantState");
        enumMatches(api.at("/components/schemas/DailyExecution/properties/costState/enum"), "DailyCostState");
        enumMatches(api.at("/components/schemas/DailyGrantView/properties/allowedPurposes/items/enum"), "DailyPurpose");
        for (String route : names(api.path("paths"))) {
            JsonNode item = api.path("paths").path(route);
            for (String method : List.of("get", "put", "post")) if (item.has(method)) {
                JsonNode operation = item.path(method);
                assertThat(operation.path("responses").has("401")).isTrue();
                for (JsonNode response : operation.path("responses")) {
                    JsonNode resolved = dereference(response, api);
                    assertThat(resolved.at("/headers/Cache-Control/schema/const").asText()).isEqualTo("no-store");
                }
                if (!method.equals("get")) assertThat(operation.at("/parameters/0/$ref").asText()).isEqualTo("#/components/parameters/Origin");
            }
            if (route.endsWith("/revoke")) {
                assertThat(item.at("/post/x-archguard-empty-body").asBoolean()).isTrue();
                assertThat(item.path("post").has("requestBody")).isFalse();
            }
        }
        for (String resource : List.of("risk-acknowledgements", "grants", "requests")) {
            JsonNode post = api.path("paths").path(PREFIX + "/" + resource).path("post");
            assertThat(post.at("/responses/201/headers/Location/schema/type").asText()).isEqualTo("string");
            assertThat(post.at("/responses/200/headers").has("Location")).isFalse();
            assertThat(post.at("/responses/201/content")).isEqualTo(post.at("/responses/200/content"));
            assertThat(post.at("/parameters/1/$ref").asText()).isEqualTo("#/components/parameters/IdempotencyKey");
        }
        JsonNode settings = api.path("paths").path(PREFIX + "/settings");
        for (String method : List.of("get", "put")) assertThat(settings.path(method).at("/responses/200/headers/ETag/schema/pattern").asText()).isNotEmpty();
        assertThat(settings.at("/put/responses").has("412")).isTrue();
        assertThat(settings.at("/put/responses").has("428")).isTrue();
        Schema settingsInput = schema(api.at("/components/schemas/DailySettingsInput"));
        ObjectNode disabled = mapper.createObjectNode().put("schemaVersion", "0.1.0").put("enabled", false).putNull("grantId");
        valid(settingsInput, disabled);
        invalid(settingsInput, disabled.deepCopy().put("enabled", "false"));
        invalid(settingsInput, disabled.deepCopy().put("grantId", ID));
        invalid(settingsInput, disabled.deepCopy().put("enabled", true));
        valid(settingsInput, disabled.deepCopy().put("enabled", true).put("grantId", ID));
        JsonNode execution = api.at("/components/schemas/DailyExecution");
        ObjectNode notAttempted = mapper.createObjectNode().putNull("attemptId").putNull("accountingDay")
            .put("costState", "NOT_ATTEMPTED").putNull("reservedMicrousd").putNull("actualMicrousd");
        valid(schema(execution), notAttempted);
        invalid(schema(execution), notAttempted.deepCopy().put("reservedMicrousd", 0));
        for (String costState : List.of("PENDING", "UNKNOWN", "SETTLED")) {
            ObjectNode attempted = notAttempted.deepCopy().put("attemptId", ID).put("accountingDay", "2026-10-29")
                .put("costState", costState).put("reservedMicrousd", 316373);
            if (costState.equals("SETTLED")) { attempted.put("actualMicrousd", 0); valid(schema(execution), attempted); attempted.putNull("actualMicrousd"); invalid(schema(execution), attempted); }
            else { valid(schema(execution), attempted); invalid(schema(execution), attempted.deepCopy().put("actualMicrousd", 0)); }
        }
        assertThat(api.at("/components/responses/Error/content/application~1json/schema/$ref").asText())
            .isEqualTo("./agent-v1.json#/components/schemas/ApiError");
        Class<?> operations = Class.forName("io.github.aiarchguard.platform.agent.DailyAgentOperations");
        assertThat(Arrays.stream(operations.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
            .containsExactlyInAnyOrder("authorizeSelectionWrite", "authorizeControlWrite", "context", "settings", "updateSettings",
                "acknowledge", "getRisk", "revokeRisk", "approveGrant", "getGrant", "revokeGrant", "create", "get", "lookup", "list");
        assertThat(dailyType("Creation").getRecordComponents()).extracting(RecordComponent::getName).containsExactly("value", "created");
        assertThat(api.at("/components/schemas").has("Creation")).isFalse();
        for (Map.Entry<String, String> entry : OLD_CONTRACT_SHA256.entrySet()) {
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(entry.getKey())))))
                .as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    private ObjectNode fixture(String kind) throws Exception {
        return (ObjectNode) mapper.readTree(Path.of("src/test/resources/agent-daily/" + kind + "-valid.json").toFile());
    }
    private Schema schema(JsonNode definition) throws Exception {
        try (var stream = new ByteArrayInputStream(mapper.writeValueAsBytes(definition))) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
        }
    }
    private void valid(Schema schema, JsonNode body) {
        assertThat(schema.validate(body.toString(), InputFormat.JSON,
            context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))).as(body.toString()).isEmpty();
    }
    private void invalid(Schema schema, JsonNode body) {
        assertThat(schema.validate(body.toString(), InputFormat.JSON,
            context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))).as(body.toString()).isNotEmpty();
    }
    private Class<?> dailyType(String name) throws Exception { return Class.forName("io.github.aiarchguard.platform.agent.DailyAgentOperations$" + name); }
    private void dto(JsonNode definition, String type) throws Exception {
        String[] fields = Arrays.stream(dailyType(type).getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new);
        assertThat(names(definition.path("properties"))).as(type).containsExactlyInAnyOrder(fields);
        assertThat(strings(definition.path("required"))).as(type).containsExactlyInAnyOrder(fields);
    }
    private void enumMatches(JsonNode definition, String type) throws Exception {
        assertThat(strings(definition)).containsExactly(Arrays.stream(dailyType(type).getEnumConstants()).map(Object::toString).toArray(String[]::new));
    }
    private List<String> names(JsonNode value) { List<String> result = new ArrayList<>(); value.fieldNames().forEachRemaining(result::add); return result; }
    private List<String> strings(JsonNode value) { List<String> result = new ArrayList<>(); value.forEach(item -> result.add(item.asText())); return result; }
    private JsonNode dereference(JsonNode node, JsonNode root) { return node.has("$ref") ? root.at(node.path("$ref").asText().substring(1)) : node; }
    // 全部新对象均严格，条件约束中的 properties 不被误认为完整对象声明。
    private void strict(JsonNode value) {
        if (value.isObject() && value.path("type").asText().equals("object")) {
            assertThat(value.path("additionalProperties").isBoolean()).isTrue();
            assertThat(value.path("additionalProperties").asBoolean()).isFalse();
            assertThat(strings(value.path("required"))).containsExactlyInAnyOrderElementsOf(names(value.path("properties")));
        }
        if (value.isContainerNode()) value.forEach(this::strict);
    }
    // 递归解析所有本地文件和 JSON Pointer，拒绝意外远程引用；访问集避免引用循环。
    private void resolve(JsonNode value, JsonNode root, Path file, Set<String> visited) throws Exception {
        if (value.isObject() && value.has("$ref")) {
            String ref = value.path("$ref").asText();
            assertThat(ref).doesNotContain("://"); String[] parts = ref.split("#", 2);
            Path targetFile = parts[0].isEmpty() ? file : file.getParent().resolve(parts[0]).normalize();
            JsonNode targetRoot = parts[0].isEmpty() ? root : mapper.readTree(targetFile.toFile());
            JsonNode target = parts.length == 1 ? targetRoot : targetRoot.at(parts[1]);
            assertThat(target.isMissingNode()).as(file + " -> " + ref).isFalse();
            if (visited.add(targetFile + "#" + (parts.length == 1 ? "" : parts[1]))) resolve(target, targetRoot, targetFile, visited);
        }
        if (value.isContainerNode()) for (JsonNode child : value) resolve(child, root, file, visited);
    }

    // 基线 62ac4bcae20a3ada5a9dbdaf679d97695c1fb04e 的工作树原字节；不能重生成旧文件。
    private static final Map<String, String> OLD_CONTRACT_SHA256 = Map.ofEntries(
        Map.entry("contracts/agent-batch-preview-0.1.0.schema.json", "c62d18f8f92d942349027901bb41d775a59a677e3004914a0379175f845e04d0"),
        Map.entry("contracts/agent-live-batch-0.1.0.schema.json", "c1bd68ef981ff5ae26d82e636861404e5dd2830cf3be7ff222ee7695e2efa945"),
        Map.entry("contracts/agent-live-batch-0.2.0.schema.json", "aaa9d040b3fff745756fa1206e1970515fd9808ca6617f828369d7fa21206ca7"),
        Map.entry("contracts/agent-personal-enablement-0.1.0.schema.json", "b2535173085cd4aad10de2c35bfb2850e6231bd7b733e6c5bbe2fb7d9d75b0f9"),
        Map.entry("contracts/agent-personal-enablement-0.2.0.schema.json", "3852d0cc77dc27c103091a59a0ab07b3f8a7c7ba094689d3022414ecc620d6cb"),
        Map.entry("contracts/agent-personal-enablement-0.3.0.schema.json", "505c11de4be5e8dc4326098ee039ae64207584188398187cc0a87d515ef38b3b"),
        Map.entry("contracts/agent-personal-enablement-0.4.0.schema.json", "3676a511574c9cd0c9b52c25b733b263a55bd070f29b264f9614216ee95f4e39"),
        Map.entry("contracts/agent-synthetic-inventory-0.1.0.schema.json", "4cbc4ba825f80268dea7d26f16ca2b4cf62dadc135d81c9b61c6cf867f8a25b9"),
        Map.entry("openapi/agent-batches-v1.json", "d93601efe8bdb9031811eb5159dfffbf76e2245aaca95517eba994b92e1a3647"),
        Map.entry("openapi/agent-credentials-v1.json", "fecf5b689fb20c8a30f7c6c4b00b585de31ae814b5f342fda192fdd809cd3dcd"), // 公共契约的 SHA-256 校验值，非凭据。gitleaks:allow
        Map.entry("openapi/agent-documents-v1.json", "2c095a835b2a1b449d11bdc9c1643de19e01ac929bdf108ece0bb81286a056cc"),
        Map.entry("openapi/agent-enablement-v1.json", "26aaf77490c8dafe649c925db6df4eda834422d5dbe68e6b0fe322a65b73f91c"),
        Map.entry("openapi/agent-live-v1.json", "5fcd61ddbba84b8611d8700b21af90023b6bf61c2e7b5f14c4204eb9ef0cb572"),
        Map.entry("openapi/agent-v1.json", "20fe1e8ea1cd174a64231973bfd65d743a12575334f4fad5893536b3ed934c55"),
        Map.entry("openapi/governance-baselines-v1.json", "b0d76c730e0a2f2989b2bd3b18abe3706c867783e59eee042f4ace4325d201f6"),
        Map.entry("openapi/governance-gates-v1.json", "7586c9b59254861427a1cf0df3eaafebea95420e642747280eb43a8dedebab4a"),
        Map.entry("openapi/governance-github-v1.json", "fc11822b0c526d7f8e9c2140ca00cb4a29a6f9518e3df41f65bc7b625fed68b5"),
        Map.entry("openapi/governance-read-v1.json", "193034bbbc9d189989155106e9f876f27fa6b1ebf80e720bb2fa6781c0908a50"),
        Map.entry("openapi/governance-v1.json", "97b05c66fdbe2b5439834cc1fa591e6a0ef7cdb9fba24bc591302f4609e9c521"),
        Map.entry("openapi/platform-v1.yaml", "1c5343aa875419cce26069078d027b7d34e874f523a20ea34b35ef14a07a4069"),
        Map.entry("src/main/resources/contracts/agent-model-output-0.1.0.schema.json", "e591f964463040bd4a0a84197604f603ca86750b7b8d783e0df546866880ab4f"),
        Map.entry("src/main/resources/contracts/scan-result-0.1.0.schema.json", "6359d6dff4633d004efe39316bfcdb744dcba82c3a56e5f07a1d075b69cd36aa"));
}
