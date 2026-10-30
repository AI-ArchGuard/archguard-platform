package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
        for (Map.Entry<String, ContractDigests> entry : OLD_CONTRACT_SHA256.entrySet()) {
            assertOldContractBytes(entry.getKey(), Files.readAllBytes(Path.of(entry.getKey())));
        }
    }

    // 固定 Git LF 原件的完整字节；fixture 不依赖当前检出换行或 JUnit 中的 Git 命令。
    private byte[] baselinePreviewLf() {
        return Base64.getDecoder().decode(
            "ewogICIkc2NoZW1hIjogImh0dHBzOi8vanNvbi1zY2hlbWEub3JnL2RyYWZ0LzIwMjAtMTIvc2NoZW1hIiwKICAiJGlkIjogImh0" +
            "dHBzOi8vYXJjaGd1YXJkLmV4YW1wbGUvY29udHJhY3RzL2FnZW50LWJhdGNoLXByZXZpZXctMC4xLjAuc2NoZW1hLmpzb24iLAog" +
            "ICJ0aXRsZSI6ICJQZXJzb25hbCBzeW50aGV0aWMgYmF0Y2ggbWFuaWZlc3QgcHJldmlldyBpbnB1dCIsCiAgImRlc2NyaXB0aW9u" +
            "IjogIlJlYWQtb25seSBjYWxjdWxhdGlvbiBhZnRlciBvd25lciwgUHJvamVjdCBNYWludGFpbmVyIGFuZCBPcmlnaW4gYXV0aG9y" +
            "aXphdGlvbi4gTm8gYmF0Y2gsIHJlcXVlc3QsIGF0dGVtcHQsIGZlZSByZXNlcnZhdGlvbiBvciBtb2RlbCBjYWxsIGlzIGNyZWF0" +
            "ZWQuIFN0cmljdCBKU09OLCBkdXBsaWNhdGUga2V5cyBhbmQgdHJhaWxpbmcgY29udGVudCBhcmUgcmVqZWN0ZWQ7IHRoZSBjb21w" +
            "bGV0ZSBIVFRQIGJvZHkgaXMgbGltaXRlZCB0byA4MTkyIGJ5dGVzLiIsCiAgInR5cGUiOiAib2JqZWN0IiwKICAiYWRkaXRpb25h" +
            "bFByb3BlcnRpZXMiOiBmYWxzZSwKICAicmVxdWlyZWQiOiBbInRlbXBsYXRlUmVxdWVzdElkcyJdLAogICJwcm9wZXJ0aWVzIjog" +
            "ewogICAgInRlbXBsYXRlUmVxdWVzdElkcyI6IHsKICAgICAgInR5cGUiOiAiYXJyYXkiLAogICAgICAibWluSXRlbXMiOiAxLAog" +
            "ICAgICAibWF4SXRlbXMiOiAyMCwKICAgICAgInVuaXF1ZUl0ZW1zIjogdHJ1ZSwKICAgICAgIml0ZW1zIjogeyJ0eXBlIjogInN0" +
            "cmluZyIsICJmb3JtYXQiOiAidXVpZCJ9LAogICAgICAiZGVzY3JpcHRpb24iOiAiRXhpc3RpbmcgaW1tdXRhYmxlIEFnZW50IHJl" +
            "cXVlc3QgdGVtcGxhdGVzIGZyb20gdGhlIGF1dGhvcml6ZWQgUHJvamVjdCBvbmx5LiBTZXJ2ZXIgc29ydHMgSURzIGFuZCBjYWxj" +
            "dWxhdGVzIHRoZSBtYW5pZmVzdDsgbmVpdGhlciByYXcgaW5wdXQgbm9yIGNpdGF0aW9uIGNvbnRlbnQgaXMgcmV0dXJuZWQuIgog" +
            "ICAgfQogIH0KfQo=");
    }

    // 只认两个已知物理表示；遗漏 Git LF 摘要会在 Windows 上同样复现 Linux 检出缺陷。
    @Test void compatibilityGuardAcceptsExactLfAndCrlfBaseline() throws Exception {
        byte[] lf = baselinePreviewLf();
        byte[] crlf = new String(lf, StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        assertAll(
            () -> assertOldContractBytes("contracts/agent-batch-preview-0.1.0.schema.json", lf),
            () -> assertOldContractBytes("contracts/agent-batch-preview-0.1.0.schema.json", crlf));
    }

    // 内容变更、混合换行和孤立 CR 不能借助换行兼容绕过原字节保护。
    @Test void compatibilityGuardRejectsContentChangesMixedAndLoneCarriageReturns() throws Exception {
        String lf = new String(baselinePreviewLf(), StandardCharsets.UTF_8);
        String crlf = lf.replace("\n", "\r\n");
        for (String changed : List.of(lf.replaceFirst("templateRequestIds", "templateRequestIdX"),
                crlf.replaceFirst("templateRequestIds", "templateRequestIdX"),
                lf.replaceFirst("\n", "\r\n"), lf.replaceFirst("\n", "\r"), lf.replace("\n", "\r"))) {
            assertThatThrownBy(() -> assertOldContractBytes("contracts/agent-batch-preview-0.1.0.schema.json",
                changed.getBytes(StandardCharsets.UTF_8))).isInstanceOf(AssertionError.class);
        }
    }

    private void assertOldContractBytes(String path, byte[] actual) throws Exception {
        ContractDigests expected = OLD_CONTRACT_SHA256.get(path);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)))
            .as(path).isIn(expected.lf(), expected.crlf());
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

    // 固定 Git 基线 5b87102f109bd0ae703248a549a4b902c155b1f4：LF 原字节及其完整 CRLF 检出表示。
    // 实际文件不做归一化；混合换行、孤立 CR 和任何内容修改均不能匹配这两个摘要。
    private record ContractDigests(String lf, String crlf) {}
    private static final Map<String, ContractDigests> OLD_CONTRACT_SHA256 = Map.ofEntries(
        Map.entry("contracts/agent-batch-preview-0.1.0.schema.json", new ContractDigests("cfe1999ba15090044fd62d11c9d8d1603679b1fd52d8b03be88e987670d448a9", "c62d18f8f92d942349027901bb41d775a59a677e3004914a0379175f845e04d0")),
        Map.entry("contracts/agent-live-batch-0.1.0.schema.json", new ContractDigests("396f8c6690097cba65d6794d53a55a44fb668589bd68da70815a99ed94877289", "c1bd68ef981ff5ae26d82e636861404e5dd2830cf3be7ff222ee7695e2efa945")),
        Map.entry("contracts/agent-live-batch-0.2.0.schema.json", new ContractDigests("aaa9d040b3fff745756fa1206e1970515fd9808ca6617f828369d7fa21206ca7", "3573bd684d588044ed6b155a17dfb54db4b7b5126e657a71f25046c3c88cfec6")),
        Map.entry("contracts/agent-personal-enablement-0.1.0.schema.json", new ContractDigests("e64deb7228440e1b6224ccc3b537d195dab8ac5fd47d976c223ccd0e6f11d885", "b2535173085cd4aad10de2c35bfb2850e6231bd7b733e6c5bbe2fb7d9d75b0f9")),
        Map.entry("contracts/agent-personal-enablement-0.2.0.schema.json", new ContractDigests("36c415192c7b46b92228a9ec1425522fdf2ff7c63dc9a551afeb45a4d4bd9852", "3852d0cc77dc27c103091a59a0ab07b3f8a7c7ba094689d3022414ecc620d6cb")),
        Map.entry("contracts/agent-personal-enablement-0.3.0.schema.json", new ContractDigests("505c11de4be5e8dc4326098ee039ae64207584188398187cc0a87d515ef38b3b", "9e6393241fc82f2f670592a5ece8a5f434856940a53422161c960f0b21807068")),
        Map.entry("contracts/agent-personal-enablement-0.4.0.schema.json", new ContractDigests("3676a511574c9cd0c9b52c25b733b263a55bd070f29b264f9614216ee95f4e39", "a169e1f5d55a286d7983094a3d0f476ee8da44100deeda7a28708a0017c70d74")),
        Map.entry("contracts/agent-synthetic-inventory-0.1.0.schema.json", new ContractDigests("56c5ce713a00775f4abd549b1c4ded2949402373443604e80d32468491c7ab32", "4cbc4ba825f80268dea7d26f16ca2b4cf62dadc135d81c9b61c6cf867f8a25b9")),
        Map.entry("openapi/agent-batches-v1.json", new ContractDigests("120130fd62149fa63988634191157adc20b8aa7f713b54bdd8db784dabf9f34a", "d93601efe8bdb9031811eb5159dfffbf76e2245aaca95517eba994b92e1a3647")),
        Map.entry("openapi/agent-credentials-v1.json", new ContractDigests("dfb198588b6a11cc31c4d15ac114ad47c06bfd524baf95c3e1159dfb82bce639", "fecf5b689fb20c8a30f7c6c4b00b585de31ae814b5f342fda192fdd809cd3dcd")), // 公共契约的 SHA-256 校验值，非凭据。gitleaks:allow
        Map.entry("openapi/agent-documents-v1.json", new ContractDigests("a929b6946acddd11a99c4e8b046566c00dc00d3853d0b7274a2cb5a6cb12535c", "2c095a835b2a1b449d11bdc9c1643de19e01ac929bdf108ece0bb81286a056cc")),
        Map.entry("openapi/agent-enablement-v1.json", new ContractDigests("65b61c1c389a4be0b0052b3cfa35231044d685cc34e1d84cd9515cf93596cb61", "26aaf77490c8dafe649c925db6df4eda834422d5dbe68e6b0fe322a65b73f91c")),
        Map.entry("openapi/agent-live-v1.json", new ContractDigests("5fcd61ddbba84b8611d8700b21af90023b6bf61c2e7b5f14c4204eb9ef0cb572", "092b0b1051964094f3df4eea5c6db368ddbee5dbcb206bb5e0172c75a4ea5bbe")),
        Map.entry("openapi/agent-v1.json", new ContractDigests("3cf220e5279fa436ed8e8f724f452ba9d862dc5951fb2c19aa3ba1989f28dba3", "20fe1e8ea1cd174a64231973bfd65d743a12575334f4fad5893536b3ed934c55")),
        Map.entry("openapi/governance-baselines-v1.json", new ContractDigests("c88128c314e929b4092ea28463acce30b0a3a824b23a2c6700e9b535643390a6", "b0d76c730e0a2f2989b2bd3b18abe3706c867783e59eee042f4ace4325d201f6")),
        Map.entry("openapi/governance-gates-v1.json", new ContractDigests("64bd78a8d3d118c93334c11631bd4c663b3c340af609df4b455d8aa04a587697", "7586c9b59254861427a1cf0df3eaafebea95420e642747280eb43a8dedebab4a")),
        Map.entry("openapi/governance-github-v1.json", new ContractDigests("c064c94be753b3e42f07a97af14dd4713801b7e2e8fe1e35916d41b64b226449", "fc11822b0c526d7f8e9c2140ca00cb4a29a6f9518e3df41f65bc7b625fed68b5")),
        Map.entry("openapi/governance-read-v1.json", new ContractDigests("d638921f74cb3f65f0d0ef96c093906f32f41561bb993e7a99672e0afd20b58e", "193034bbbc9d189989155106e9f876f27fa6b1ebf80e720bb2fa6781c0908a50")),
        Map.entry("openapi/governance-v1.json", new ContractDigests("47319b4b2e9a5c47e7f89638a3a8e39dc87106afcff048987481f65fb4ef9f9e", "97b05c66fdbe2b5439834cc1fa591e6a0ef7cdb9fba24bc591302f4609e9c521")),
        Map.entry("openapi/platform-v1.yaml", new ContractDigests("962f47f037a4c23f3c0a01f3645ac79d21be2252a107527b31f4cf778d82b1f7", "1c5343aa875419cce26069078d027b7d34e874f523a20ea34b35ef14a07a4069")),
        Map.entry("src/main/resources/contracts/agent-model-output-0.1.0.schema.json", new ContractDigests("5b0c0736efa97abcbccc81c8058142eb80f30df169352f5d364d8ce7785be7f3", "e591f964463040bd4a0a84197604f603ca86750b7b8d783e0df546866880ab4f")),
        Map.entry("src/main/resources/contracts/scan-result-0.1.0.schema.json", new ContractDigests("3272963eae238a6be5a5012579485544b9a4930f329fdd465b71bdb1d3f3a75d", "6359d6dff4633d004efe39316bfcdb744dcba82c3a56e5f07a1d075b69cd36aa")));
}
