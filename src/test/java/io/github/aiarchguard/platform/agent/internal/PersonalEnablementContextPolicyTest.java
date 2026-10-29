package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.LiveBatchInput;
import io.github.aiarchguard.platform.agent.PersonalEnablementInput;
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

/**
 * 验证启用确认、费用策略和模型映射的不可变版本配对，特别是获批 1017 映射。只验证本地提案、Schema 与 API 描述，不启用出口或产生费用。
 */
class PersonalEnablementContextPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND = "22222222-2222-4222-8222-222222222222";
    private static final String LEGACY_PRICE = "deepseek-flash-peak-usd-2026-10-06";
    private static final String CONTEXT_PRICE = "deepseek-flash-context-peak-usd-2026-10-15";
    private static final String LEGACY_MAPPING = "deepseek-v4.1-flash-2026-10-06";
    private static final String CONTEXT_MAPPING = "deepseek-v4.1-flash-2026-10-15";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // 新确认必须显式提交，旧确认不能被原地升级。
    @Test void acceptsExplicitContextAcknowledgementWithoutMutatingOrUpgradingLegacy() throws Exception {
        PersonalEnablementInput legacy = input(legacy());
        PersonalEnablementInput context = input(context());
        assertThatCode(() -> PersonalEnablementPolicy.validate(legacy, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> PersonalEnablementPolicy.validate(context, NOW)).doesNotThrowAnyException();
        assertThat(legacy.schemaVersion()).isEqualTo("0.2.0");
        assertThat(legacy.priceCatalogVersion()).isEqualTo(LEGACY_PRICE);
        assertThat(legacy.mappingSnapshot()).isEqualTo(LEGACY_MAPPING);
        assertThat(context.schemaVersion()).isEqualTo("0.3.0");
        assertThat(context.priceCatalogVersion()).isEqualTo(CONTEXT_PRICE);
        assertThat(context.mappingSnapshot()).isEqualTo(CONTEXT_MAPPING);
    }

    // 1017 绑定保持获批请求别名和上下文价格不变。
    @Test void acceptsExplicitApproved1017AcknowledgementWithUnchangedAliasAndContextPrice() throws Exception {
        PersonalEnablementInput approved = input(approved1017());
        assertThatCode(() -> PersonalEnablementPolicy.validate(approved, NOW)).doesNotThrowAnyException();
        assertThat(approved.schemaVersion()).isEqualTo("0.4.0");
        assertThat(approved.mappingSnapshot()).isEqualTo("DeepSeek-V4.1-Flash-1017");
        assertThat(approved.modelAlias()).isEqualTo("deepseek-flash");
        assertThat(approved.allowedResponseModels()).containsExactly("deepseek-flash");
        assertThat(approved.priceCatalogVersion()).isEqualTo("deepseek-flash-context-peak-usd-2026-10-15");
    }

    // Schema、映射、价格与允许响应模型必须成套匹配，拒绝混搭。
    @Test void approved1017CannotBeMixedWithOlderSchemasMappingsPricesOrResponseModelNames() throws Exception {
        for (String schema : List.of("0.2.0", "0.3.0")) rejected(approved1017().put("schemaVersion", schema));
        for (String mapping : List.of(LEGACY_MAPPING, CONTEXT_MAPPING, "deepseek-v4.1-flash-1017", "deepseek-flash")) {
            rejected(approved1017().put("mappingSnapshot", mapping));
        }
        rejected(approved1017().put("priceCatalogVersion", LEGACY_PRICE));
        rejected(approved1017().put("modelAlias", "DeepSeek-V4.1-Flash-1017"));
        for (List<String> models : List.of(List.<String>of(), List.of("DeepSeek-V4.1-Flash-1017"),
                List.of("deepseek-flash", "DeepSeek-V4.1-Flash-1017"), List.of("deepseek-flash", "deepseek-flash"))) {
            ObjectNode body = approved1017();
            ArrayNode allowed = body.putArray("allowedResponseModels");
            models.forEach(allowed::add);
            rejected(body);
        }
    }

    // 新映射不能放宽原风险确认、来源新鲜度、期限和凭据要求。
    @Test void approved1017KeepsOriginalRiskDataSourceExpiryAndCredentialGates() throws Exception {
        rejected(approved1017().put("riskAccepted", false));
        rejected(approved1017().put("scope", "ENTERPRISE"));
        rejected(approved1017().put("dataScope", "PERSONAL_DAILY_USE"));
        rejected(approved1017().put("expiresAt", NOW.toString()));
        rejected(approved1017().put("priceCatalogExpiresAt", NOW.toString()));
        rejected(approved1017().put("expiresAt", NOW.plusSeconds(7 * 86400L + 1).toString()));
        rejected(approved1017().put("priceCatalogExpiresAt", NOW.plusSeconds(7 * 86400L + 1).toString()));
        rejected(approved1017().put("priceCatalogExpiresAt", NOW.plusSeconds(1).toString()));
        rejected(approved1017().put("secretCheckRef", "sk-synthetic-not-a-key"));
        rejected(approved1017().put("networkCheckRef", ""));
        ObjectNode missingCredential = approved1017(); missingCredential.remove("credentialVersion"); rejected(missingCredential);
        ObjectNode unknowns = approved1017(); unknowns.putArray("unknowns"); rejected(unknowns);
        ObjectNode revocations = approved1017(); revocations.putArray("revocationConditions"); rejected(revocations);
        ObjectNode sources = approved1017(); sources.putArray("sources"); rejected(sources);
        ObjectNode stale = approved1017(); ((ObjectNode) stale.path("sources").get(0))
            .put("checkedAt", NOW.minusSeconds(7 * 86400L + 1).toString()); rejected(stale);
        ObjectNode mismatch = approved1017(); ((ObjectNode) mismatch.path("sources").get(0))
            .put("url", "https://api-docs.deepseek.com/quick_start/pricing/"); rejected(mismatch);
    }

    @Test void approved1017ProducerSchemaIsStrictAndIsolatesAllThreeImmutablePairings() throws Exception {
        Schema current = fileSchema("contracts/agent-personal-enablement-0.4.0.schema.json");
        Schema previous = fileSchema("contracts/agent-personal-enablement-0.3.0.schema.json");
        Schema legacy = fileSchema("contracts/agent-personal-enablement-0.2.0.schema.json");
        valid(current, approved1017()); invalid(current, context()); invalid(current, legacy());
        invalid(previous, approved1017()); valid(previous, context());
        invalid(legacy, approved1017()); valid(legacy, legacy());
        JsonNode definition = json("contracts/agent-personal-enablement-0.4.0.schema.json");
        assertThat(names(definition.path("properties"))).containsExactlyInAnyOrder(recordNames(PersonalEnablementInput.class));
        assertThat(strings(definition.path("required"))).containsExactlyInAnyOrder(recordNames(PersonalEnablementInput.class));
        for (String field : List.of("apiKey", "inputTokenLimit", "maxCostMicrousd", "policyVersion", "endpoint", "approvedBy")) {
            invalid(current, approved1017().put(field, "synthetic-forbidden-field"));
        }
        for (String field : recordNames(PersonalEnablementInput.class)) {
            ObjectNode missing = approved1017(); missing.remove(field); invalid(current, missing);
        }
        for (String version : List.of("0.2.0", "0.3.0")) invalid(current, approved1017().put("schemaVersion", version));
        for (String mapping : List.of(LEGACY_MAPPING, CONTEXT_MAPPING, "deepseek-v4.1-flash-1017")) {
            invalid(current, approved1017().put("mappingSnapshot", mapping));
        }
        invalid(current, approved1017().put("priceCatalogVersion", LEGACY_PRICE));
        invalid(current, approved1017().put("modelAlias", "DeepSeek-V4.1-Flash-1017"));
        invalid(current, approved1017().put("riskAccepted", false));
        ObjectNode models = approved1017(); models.putArray("allowedResponseModels").add("DeepSeek-V4.1-Flash-1017"); invalid(current, models);
        ObjectNode extraModel = approved1017(); extraModel.withArray("allowedResponseModels").add("DeepSeek-V4.1-Flash-1017"); invalid(current, extraModel);
        ObjectNode extraSource = approved1017(); ((ObjectNode) extraSource.path("sources").get(0)).put("apiKey", "synthetic-forbidden"); invalid(current, extraSource);
    }

    @Test void schemaPriceAndMappingMustMatchExactlyWithoutHybridUpgrade() throws Exception {
        for (ObjectNode original : List.of(legacy(), context())) {
            boolean isLegacy = original.path("schemaVersion").asText().equals("0.2.0");
            rejected(original.deepCopy().put("priceCatalogVersion", isLegacy ? CONTEXT_PRICE : LEGACY_PRICE));
            rejected(original.deepCopy().put("mappingSnapshot", isLegacy ? CONTEXT_MAPPING : LEGACY_MAPPING));
            rejected(original.deepCopy().put("schemaVersion", isLegacy ? "0.3.0" : "0.2.0"));
        }
        rejected(context().put("schemaVersion", "0.4.0"));
        rejected(context().put("priceCatalogVersion", "latest"));
        rejected(context().putNull("schemaVersion"));
    }

    @Test void contextDoesNotRelaxModelPurposeRiskOrCredentialRequirements() throws Exception {
        rejected(context().put("riskAccepted", false));
        rejected(context().put("scope", "ENTERPRISE"));
        rejected(context().put("dataScope", "PERSONAL_DAILY_USE"));
        rejected(context().put("modelAlias", "another-model"));
        ObjectNode missingCredential = context(); missingCredential.remove("credentialVersion"); rejected(missingCredential);
        ObjectNode model = context(); model.putArray("allowedResponseModels").add("deepseek-flash").add("unapproved-model"); rejected(model);
        assertThatThrownBy(() -> PersonalEnablementPolicy.validate(null, NOW)).isInstanceOf(AgentInvalidException.class).hasNoCause();
    }

    @Test void contextKeepsExpiryAndRecentOfficialSourceChecks() throws Exception {
        rejected(context().put("expiresAt", NOW.toString()));
        rejected(context().put("expiresAt", NOW.plusSeconds(7 * 86400L + 1).toString()));
        rejected(context().put("priceCatalogExpiresAt", NOW.toString()));
        rejected(context().put("priceCatalogExpiresAt", NOW.plusSeconds(7 * 86400L + 1).toString()));
        rejected(context().put("priceCatalogExpiresAt", NOW.plusSeconds(1).toString()));
        ObjectNode future = context(); ((ObjectNode) future.path("sources").get(0)).put("checkedAt", NOW.plusSeconds(1).toString()); rejected(future);
        ObjectNode stale = context(); ((ObjectNode) stale.path("sources").get(0)).put("checkedAt", NOW.minusSeconds(7 * 86400L + 1).toString()); rejected(stale);
        ObjectNode inclusive = context();
        inclusive.put("expiresAt", NOW.plusSeconds(7 * 86400L).toString()).put("priceCatalogExpiresAt", NOW.plusSeconds(7 * 86400L).toString());
        ((ObjectNode) inclusive.path("sources").get(0)).put("checkedAt", NOW.minusSeconds(7 * 86400L).toString());
        assertThatCode(() -> PersonalEnablementPolicy.validate(input(inclusive), NOW)).doesNotThrowAnyException();
    }

    @Test void contextStillRequiresAllUnknownsRevocationsAndExactOfficialSourcePairs() throws Exception {
        ObjectNode unknowns = context(); ((ArrayNode) unknowns.path("unknowns")).set(0, mapper.getNodeFactory().textNode("STORAGE_REGION")); rejected(unknowns);
        ObjectNode revocations = context(); ((ArrayNode) revocations.path("revocationConditions")).set(0, mapper.getNodeFactory().textNode("SCOPE_CHANGED")); rejected(revocations);
        ObjectNode wrongPair = context(); ((ObjectNode) wrongPair.path("sources").get(0)).put("url", "https://api-docs.deepseek.com/quick_start/pricing/"); rejected(wrongPair);
        ObjectNode duplicate = context(); ((ArrayNode) duplicate.path("sources")).set(0, duplicate.path("sources").get(1).deepCopy()); rejected(duplicate);
        ObjectNode unapproved = context(); ((ObjectNode) unapproved.path("sources").get(0)).put("url", "https://unapproved.example.invalid/terms"); rejected(unapproved);
        for (String field : List.of("accountRef", "deploymentRef", "secretCheckRef", "networkCheckRef")) {
            for (String value : List.of("", "sk-synthetic-not-a-key", "https://synthetic.example/review", "C:/synthetic", "a".repeat(129))) {
                rejected(context().put(field, value));
            }
        }
    }

    // 版本变更通过显式值区分，不扩大 DTO 字段或混淆历史策略。
    @Test void acknowledgementSchemasKeepUnchangedDtoShapeAndIsolatePolicyVersions() throws Exception {
        Schema previous = fileSchema("contracts/agent-personal-enablement-0.2.0.schema.json");
        Schema current = fileSchema("contracts/agent-personal-enablement-0.3.0.schema.json");
        valid(previous, legacy()); invalid(previous, context());
        valid(current, context()); invalid(current, legacy());
        JsonNode definition = json("contracts/agent-personal-enablement-0.3.0.schema.json");
        assertThat(names(definition.path("properties"))).containsExactlyInAnyOrder(recordNames(PersonalEnablementInput.class));
        assertThat(strings(definition.path("required"))).containsExactlyInAnyOrder(recordNames(PersonalEnablementInput.class));
        for (String field : List.of("apiKey", "inputTokenLimit", "maxCostMicrousd", "policyVersion", "endpoint", "approvedBy")) {
            invalid(current, context().put(field, "synthetic-forbidden-field"));
        }
        invalid(current, context().put("riskAccepted", false));
        invalid(current, context().put("priceCatalogVersion", LEGACY_PRICE));
        invalid(current, context().put("mappingSnapshot", LEGACY_MAPPING));
        for (String field : recordNames(PersonalEnablementInput.class)) {
            ObjectNode missing = context(); missing.remove(field); invalid(current, missing);
        }
    }

    // 批次保持既有字段形状，仅允许获批数量和费用内的唯一模板。
    @Test void batchSchemaHasEightFieldsAndOnlyTwoUniqueTemplatesWithinAuthorizedCost() throws Exception {
        Schema previous = fileSchema("contracts/agent-live-batch-0.1.0.schema.json");
        Schema current = fileSchema("contracts/agent-live-batch-0.2.0.schema.json");
        ObjectNode body = batch(); valid(current, body); invalid(previous, body);
        valid(current, body.deepCopy().put("maxCostMicrousd", 700000));
        for (long cost : new long[] {4200, 632745, 700001}) invalid(current, body.deepCopy().put("maxCostMicrousd", cost));
        for (int count : new int[] {0, 1, 3, 20}) invalid(current, body.deepCopy().put("maxRequests", count));
        invalid(current, body.deepCopy().put("callsApproved", false));
        ObjectNode one = body.deepCopy(); one.putArray("templateRequestIds").add(ID); invalid(current, one);
        ObjectNode duplicate = body.deepCopy(); duplicate.putArray("templateRequestIds").add(ID).add(ID); invalid(current, duplicate);
        ObjectNode three = body.deepCopy(); ((ArrayNode) three.path("templateRequestIds")).add(UUID.randomUUID().toString()); invalid(current, three);
        for (String field : List.of("schemaVersion", "policyVersion", "priceCatalogVersion", "apiKey", "approvedBy")) {
            invalid(current, body.deepCopy().put(field, "synthetic-forbidden-field"));
        }
        JsonNode definition = json("contracts/agent-live-batch-0.2.0.schema.json");
        assertThat(names(definition.path("properties"))).containsExactlyInAnyOrder(recordNames(LiveBatchInput.class));
        assertThat(strings(definition.path("required"))).containsExactlyInAnyOrder(recordNames(LiveBatchInput.class));
        ObjectNode old = batch(); old.putArray("templateRequestIds").add(ID); old.put("maxRequests", 1).put("maxCostMicrousd", 4200);
        valid(previous, old); invalid(current, old);
    }

    // 历史可读与当前可用分开，契约接受历史版本不等于获得发送许可。
    @Test void apiAcceptsExplicitOldAndNewContractsWithDefaultClosedRuntimeAvailability() throws Exception {
        JsonNode enablement = json("openapi/agent-enablement-v1.json");
        JsonNode batches = json("openapi/agent-batches-v1.json");
        assertThat(enablement.at("/info/version").asText()).isEqualTo("0.4.0");
        assertThat(batches.at("/info/version").asText()).isEqualTo("0.3.0");
        JsonNode acknowledgementInput = enablement.path("paths").path("/api/v1/projects/{projectId}/agent/enablements")
            .at("/post/requestBody/content/application~1json/schema/oneOf");
        assertThat(refs(acknowledgementInput)).containsExactlyInAnyOrder("../contracts/agent-personal-enablement-0.2.0.schema.json", "../contracts/agent-personal-enablement-0.3.0.schema.json", "../contracts/agent-personal-enablement-0.4.0.schema.json");
        JsonNode batchInput = batches.path("paths").path("/api/v1/projects/{projectId}/agent/batches")
            .at("/post/requestBody/content/application~1json/schema/oneOf");
        assertThat(refs(batchInput)).containsExactlyInAnyOrder("../contracts/agent-live-batch-0.1.0.schema.json", "../contracts/agent-live-batch-0.2.0.schema.json");
        assertThat(strings(enablement.at("/components/schemas/Enablement/properties/priceCatalogVersion/enum")))
            .containsExactlyInAnyOrder(LEGACY_PRICE, CONTEXT_PRICE);
        assertThat(enablement.at("/components/schemas/Settings/properties/modelCallsAvailable/type").asText()).isEqualTo("boolean");
        assertThat(enablement.at("/components/schemas/Settings/properties/modelCallsAvailable/default").isBoolean()).isTrue();
        assertThat(enablement.at("/components/schemas/Settings/properties/modelCallsAvailable/default").asBoolean()).isFalse();
        assertThat(enablement.at("/components/schemas/Settings/properties/modelCallsAvailable").has("const")).isFalse();
        assertThat(batches.path("x-archguard-model-calls").asBoolean()).isFalse();
        assertThat(batches.path("paths").toString()).doesNotContain("/send", "/reserve", "/settle");
    }

    @Test void metadataSchemaAllowsBothPoliciesButRejectsHybridCountAndCostRanges() throws Exception {
        Schema schema = nodeSchema(json("openapi/agent-batches-v1.json").at("/components/schemas/Batch"));
        ObjectNode body = mapper.createObjectNode().put("id", ID).put("projectId", ID).put("enablementId", ID)
            .put("approvedBy", ID).put("approvedAt", NOW.toString()).put("manifestSha256", "a".repeat(64))
            .put("expiresAt", NOW.plusSeconds(3600).toString()).put("maxRequests", 2).put("maxCostMicrousd", 632746).put("revoked", false);
        valid(schema, body);
        valid(schema, body.deepCopy().put("maxRequests", 1).put("maxCostMicrousd", 4200));
        invalid(schema, body.deepCopy().put("maxRequests", 1));
        invalid(schema, body.deepCopy().put("maxCostMicrousd", 84001));
        invalid(schema, body.deepCopy().put("maxCostMicrousd", 632745));
        invalid(schema, body.deepCopy().put("maxCostMicrousd", 700001));
        invalid(schema, body.deepCopy().put("costPolicyVersion", CONTEXT_PRICE));
    }

    // 以下工厂只构造合成确认，按版本替换获批配对，其他风险声明仍显式存在。
    private ObjectNode legacy() { return acknowledgement("0.2.0", LEGACY_PRICE, LEGACY_MAPPING); }
    private ObjectNode context() { return acknowledgement("0.3.0", CONTEXT_PRICE, CONTEXT_MAPPING); }
    private ObjectNode approved1017() { return acknowledgement("0.4.0", CONTEXT_PRICE, "DeepSeek-V4.1-Flash-1017"); }
    private ObjectNode acknowledgement(String version, String price, String mapping) {
        ObjectNode body = mapper.createObjectNode().put("schemaVersion", version).put("scope", "PERSONAL")
            .put("accountRef", "synthetic-account").put("deploymentRef", "synthetic-deployment")
            .put("expiresAt", NOW.plusSeconds(3600).toString()).put("riskAccepted", true).put("modelAlias", "deepseek-flash")
            .put("mappingSnapshot", mapping).put("priceCatalogVersion", price).put("priceCatalogExpiresAt", NOW.plusSeconds(3600).toString())
            .put("secretCheckRef", "synthetic-secret-check").put("networkCheckRef", "synthetic-network-check")
            .put("dataScope", "SYNTHETIC_ACCEPTANCE").put("credentialVersion", ID);
        body.putArray("allowedResponseModels").add("deepseek-flash");
        ArrayNode sources = body.putArray("sources");
        source(sources, "TERMS", "https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html");
        source(sources, "PRIVACY", "https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html");
        source(sources, "CACHE", "https://api-docs.deepseek.com/guides/kv_cache/");
        source(sources, "MODEL_PRICE", "https://api-docs.deepseek.com/quick_start/pricing/");
        for (String unknown : List.of("PROCESSING_REGION", "STORAGE_REGION", "TRAINING", "HUMAN_REVIEW", "LOG_RETENTION", "CACHE_ISOLATION", "CACHE_RETENTION", "SUBPROCESSORS")) body.withArray("unknowns").add(unknown);
        for (String reason : List.of("OWNER_REVOKED", "SCOPE_CHANGED", "POLICY_CHANGED", "MODEL_CHANGED", "PRICE_CHANGED", "SECRET_CHECK_INVALID", "NETWORK_CHECK_INVALID")) body.withArray("revocationConditions").add(reason);
        return body;
    }
    private void source(ArrayNode sources, String kind, String url) {
        sources.addObject().put("kind", kind).put("url", url).put("version", "synthetic-review-2026-10-16").put("checkedAt", NOW.toString());
    }
    private ObjectNode batch() {
        ObjectNode body = mapper.createObjectNode().put("enablementId", ID).put("syntheticInventoryId", ID).put("manifestSha256", "a".repeat(64))
            .put("expiresAt", NOW.plusSeconds(3600).toString()).put("maxRequests", 2).put("maxCostMicrousd", 632746).put("callsApproved", true);
        body.putArray("templateRequestIds").add(ID).add(SECOND); return body;
    }
    private PersonalEnablementInput input(JsonNode node) throws Exception { return mapper.treeToValue(node, PersonalEnablementInput.class); }
    private void rejected(ObjectNode body) throws Exception {
        PersonalEnablementInput value = input(body);
        assertThatThrownBy(() -> PersonalEnablementPolicy.validate(value, NOW)).isInstanceOf(AgentInvalidException.class).hasNoCause();
    }
    private JsonNode json(String file) throws Exception { return mapper.readTree(Path.of(file).toFile()); }
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
    private List<String> names(JsonNode node) { List<String> result = new ArrayList<>(); node.fieldNames().forEachRemaining(result::add); return result; }
    private List<String> strings(JsonNode node) { List<String> result = new ArrayList<>(); node.forEach(value -> result.add(value.asText())); return result; }
    private List<String> refs(JsonNode array) { List<String> result = new ArrayList<>(); array.forEach(value -> result.add(value.path("$ref").asText())); return result; }
    private String[] recordNames(Class<?> type) { return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new); }
}
