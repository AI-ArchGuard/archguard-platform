package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 验证仅开启 Agent 总开关仍不能使用未经批准的提供方。完整 Spring/数据库路径应返回 MODEL_UNAVAILABLE，且不占用预算。
 */
@SpringBootTest(properties = "archguard.agent.enabled=true")
@AutoConfigureMockMvc
class AgentUnapprovedAdapterIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    // 开关不是适配器准入证据，缺少获批模型时在费用预留前失败。
    @Test void enablingFlagAloneCannotCallAnUnapprovedProvider() throws Exception {
        var fixture = AgentExplanationIntegrationTest.fixture(jdbc);
        Map<String, Object> body = new HashMap<>();
        body.put("purpose", "FINDING_EXPLANATION"); body.put("scanJobId", fixture.job());
        body.put("reportSha256", "a".repeat(64)); body.put("prHeadRevisionId", null);
        body.put("findingIds", List.of(fixture.finding())); body.put("documentVersionIds", List.of());
        var response = mapper.readTree(mvc.perform(post("/api/v1/projects/{project}/agent/requests",
            fixture.project()).with(user("11111111-1111-1111-1111-111111111111"))
            .header("Idempotency-Key", "enabled-no-adapter").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body))).andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString());
        assertThat(response.path("state").asText()).isEqualTo("FAILED");
        assertThat(response.at("/failure/code").asText()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(jdbc.sql("SELECT count(*) FROM agent.budget_usage WHERE scope_id=:id")
            .param("id", fixture.project()).query(Integer.class).single()).isZero();
    }
}
