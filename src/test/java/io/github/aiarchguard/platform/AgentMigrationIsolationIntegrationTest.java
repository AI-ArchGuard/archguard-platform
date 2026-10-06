package io.github.aiarchguard.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.internal.AgentSchemaAvailability;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AgentMigrationIsolationIntegrationTest.FailingAgentConfiguration.class)
class AgentMigrationIsolationIntegrationTest extends PostgresIntegrationTestSupport {
    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @Test void unavailableAgentSchemaDoesNotBlockCoreProjectRoutes() throws Exception {
        String key = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String created = mvc.perform(post("/api/v1/projects")
            .with(user(ACTOR).authorities(new SimpleGrantedAuthority("project:create")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("key", key, "name", "Core remains available"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID project = UUID.fromString(mapper.readTree(created).path("id").asText());
        mvc.perform(get("/api/v1/projects/{project}", project).with(user(ACTOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Core remains available"));
        var scan = AgentExplanationIntegrationTest.fixture(jdbc);
        mvc.perform(get("/api/v1/projects/{project}/scan-jobs/{job}", scan.project(), scan.job())
            .with(user(ACTOR))).andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("PASS"));
        mvc.perform(get("/api/v1/projects/{project}/agent/requests/{request}", project, UUID.randomUUID())
            .with(user(ACTOR))).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("agent.unavailable"));
        mvc.perform(get("/api/v1/projects/{project}/agent/settings", project).with(user(ACTOR)))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("agent.unavailable"));
    }

    @TestConfiguration
    static class FailingAgentConfiguration {
        @Bean @Primary AgentSchemaAvailability unavailableAgentSchema(DataSource source) {
            return new AgentSchemaAvailability(source) {
                @Override public void migrateOnReady() { /* Synthetic failure: remain unavailable. */ }
            };
        }
    }
}
