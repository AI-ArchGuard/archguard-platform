package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验证默认配置下 Agent 请求以 MODEL_DISABLED 终态持久化且不预留预算。HTTP 接受请求不代表模型已执行，夹具全部为合成领域数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentDisabledIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    // 区分 202 的请求受理与 FAILED 业务状态，并直接核对数据库没有预算副作用。
    @Test void defaultConfigurationFailsClosedWithoutBudgetReservation() throws Exception {
        var fixture = AgentExplanationIntegrationTest.fixture(jdbc);
        Map<String, Object> body = new HashMap<>();
        body.put("purpose", "FINDING_EXPLANATION"); body.put("scanJobId", fixture.job());
        body.put("reportSha256", "a".repeat(64)); body.put("prHeadRevisionId", null);
        body.put("findingIds", List.of(fixture.finding())); body.put("documentVersionIds", List.of());
        JsonNode response = mapper.readTree(mvc.perform(post("/api/v1/projects/{project}/agent/requests",
            fixture.project()).with(user("11111111-1111-1111-1111-111111111111"))
            .header("Idempotency-Key", "default-off").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body))).andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString());
        assertThat(response.path("state").asText()).isEqualTo("FAILED");
        assertThat(response.at("/failure/code").asText()).isEqualTo("MODEL_DISABLED");
        assertThat(response.path("result").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.budget_usage WHERE scope_id=:id")
            .param("id", fixture.project()).query(Integer.class).single()).isZero();
    }
}
