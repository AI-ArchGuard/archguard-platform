package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.aiarchguard.platform.agent.LiveBatchExecutionView;
import io.github.aiarchguard.platform.agent.LiveBatchView;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 离线验证批次执行只读视图契约：请求、attempt、结算和可信用量各自可选但须一致。此视图不赋予发送、补发或重试权限。
 */
class LiveBatchExecutionContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-4222-8222-222222222222");

    // 新增 GET 只提供持久化观察结果，不能扩展成操作入口。
    @Test void readOnlyPathIsAdditiveAndDoesNotExposeSendingOrRetryPermission() throws Exception {
        var api = mapper.readTree(Path.of("openapi/agent-batches-v1.json").toFile());
        var path = api.path("paths").path("/api/v1/projects/{projectId}/agent/batches/{batchId}/execution");
        assertThat(api.at("/info/version").asText()).isEqualTo("0.3.0");
        assertThat(path.has("get")).isTrue();
        assertThat(path.has("post")).isFalse();
        assertThat(path.at("/get/x-archguard-read-only").asBoolean()).isTrue();
        assertThat(path.at("/get/responses/409").isMissingNode()).isFalse();
        assertThat(path.at("/get/responses/200/headers/Cache-Control/$ref").asText()).isEqualTo("#/components/headers/NoStore");
        assertThat(api.at("/x-archguard-model-calls").asBoolean()).isFalse();
        assertThat(api.path("paths").has("/api/v1/projects/{projectId}/agent/batches/{batchId}")).isTrue();
        assertThat(api.path("components").path("schemas").toString())
            .doesNotContain("retryAllowed", "canCall", "sentCount", "remainingCalls", "credentialVersion", "modelInput", "inventoryProof");
    }

    // 各阶段事实独立存在，不能凭一项推造另一项成功。
    @Test void strictResponseIncludesIndependentOptionalRequestAttemptOutcomeAndKnownUsage() throws Exception {
        Schema schema = schema();
        ObjectNode empty = body(null, null, 0, 0, 0);
        valid(schema, empty);
        var request = new LiveBatchExecutionView.RequestExecution(ID, "RUNNING", null, "a".repeat(32), NOW, NOW);
        var pending = attempt(null);
        valid(schema, body(request, pending, 1, 316373, 0));
        valid(schema, body(request, attempt(new LiveBatchExecutionView.Outcome("SETTLED", 273L,
            new LiveTokenUsage(110, 200, 0, 310, 0), NOW)), 1, 0, 273));
        valid(schema, body(new LiveBatchExecutionView.RequestExecution(ID, "FAILED", "CITATION_INVALID", "a".repeat(32), NOW, NOW),
            attempt(new LiveBatchExecutionView.Outcome("UNKNOWN", null, null, NOW)), 1, 316373, 0));
        // The post-call observation limit is not the accounting limit: retain valid known usage above 8,000.
        valid(schema, body(request, attempt(new LiveBatchExecutionView.Outcome("SETTLED", 316373L,
            new LiveTokenUsage(1048576, 1500, 0, 1050076, 0), NOW)), 1, 0, 316373));
        for (String field : List.of("apiKey", "input", "providerBody", "retryAllowed", "canCall")) {
            ObjectNode changed = empty.deepCopy().put(field, "synthetic-forbidden-field");
            invalid(schema, changed);
        }
        ObjectNode changed = body(request, pending, 1, 316373, 0);
        ((ObjectNode) changed.path("templates").get(0).path("request")).put("idempotencyKey", "synthetic-forbidden-field");
        invalid(schema, changed);
    }

    // UNKNOWN、非法组合和超范围值应拒绝，不能被解释为零费用。
    @Test void impossibleUnknownOrUnboundedShapesAreRejectedRatherThanDescribedAsZeroCost() throws Exception {
        Schema schema = schema();
        var request = new LiveBatchExecutionView.RequestExecution(ID, "RUNNING", null, "a".repeat(32), NOW, NOW);
        ObjectNode changed = body(request, attempt(new LiveBatchExecutionView.Outcome("UNKNOWN", 0L, null, NOW)), 1, 316373, 0);
        invalid(schema, changed);
        changed = body(request, attempt(new LiveBatchExecutionView.Outcome("SETTLED", null, null, NOW)), 1, 0, 0);
        invalid(schema, changed);
        changed = body(null, null, 3, 0, 0); invalid(schema, changed);
        changed = body(null, null, 0, 0, 0); changed.put("costPolicyVersion", LiveCostPolicy.VERSION); invalid(schema, changed);
        changed = body(null, null, 0, 0, 0); changed.putArray("templates"); invalid(schema, changed);
        changed = body(request, attempt(null), 1, 316373, 0);
        ((ObjectNode) changed.path("templates").get(0).path("attempt")).put("reservedMicrousd", 4200); invalid(schema, changed);
    }

    private LiveBatchExecutionView.AttemptExecution attempt(LiveBatchExecutionView.Outcome outcome) {
        return new LiveBatchExecutionView.AttemptExecution(SECOND, LocalDate.of(2026, 10, 16), LiveCostPolicy.CONTEXT_VERSION,
            316373, NOW, outcome);
    }
    // 从视图 DTO 构造待校验响应，让 Schema 检查与实际字段形状对齐。
    private ObjectNode body(LiveBatchExecutionView.RequestExecution request, LiveBatchExecutionView.AttemptExecution attempt,
            int count, long reserved, long spent) {
        return mapper.valueToTree(new LiveBatchExecutionView("0.1.0", NOW,
            new LiveBatchView(ID, ID, ID, ID, NOW, "a".repeat(64), NOW.plusSeconds(3600), 2, 700000, false),
            LiveCostPolicy.CONTEXT_VERSION, new LiveBatchExecutionView.BatchAccounting(count, reserved, spent), null,
            List.of(new LiveBatchExecutionView.TemplateExecution(ID, "FINDING_EXPLANATION", request, attempt),
                new LiveBatchExecutionView.TemplateExecution(SECOND, "PR_SUMMARY", null, null))));
    }
    private Schema schema() throws Exception {
        ObjectNode api = (ObjectNode) mapper.readTree(Path.of("openapi/agent-batches-v1.json").toFile());
        api.put("$ref", "#/components/schemas/BatchExecution");
        try (var stream = new ByteArrayInputStream(mapper.writeValueAsBytes(api))) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
        }
    }
    private void valid(Schema schema, ObjectNode body) { assertThat(schema.validate(body.toString(), InputFormat.JSON)).isEmpty(); }
    private void invalid(Schema schema, ObjectNode body) { assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty(); }
}
