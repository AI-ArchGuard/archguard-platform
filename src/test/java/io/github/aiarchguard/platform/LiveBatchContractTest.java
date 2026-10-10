package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LiveBatchContractTest {
    @Test void batchInputIsStrictBoundedAndDoesNotAcceptSelfReportedIdentityOrSecret() throws Exception {
        var mapper = new ObjectMapper();
        try (var stream = Files.newInputStream(Path.of("contracts/agent-live-batch-0.1.0.schema.json"))) {
            var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
            ObjectNode body = (ObjectNode) mapper.readTree("""
                {"enablementId":"11111111-1111-4111-8111-111111111111","syntheticInventoryId":"22222222-2222-4222-8222-222222222222",
                 "templateRequestIds":["33333333-3333-4333-8333-333333333333"],"manifestSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "expiresAt":"2026-10-10T13:00:00Z","maxRequests":1,"maxCostMicrousd":4200,"callsApproved":true}
                """);
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isEmpty();
            for (String field : java.util.List.of("approvedBy", "apiKey", "endpoint", "model")) {
                body.put(field, "synthetic-forbidden-field");
                assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty(); body.remove(field);
            }
            body.put("callsApproved", false); assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
            body.put("callsApproved", true).put("maxRequests", 21); assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
            body.put("maxRequests", 1).put("maxCostMicrousd", 84001); assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
        }
    }
}
