package io.github.aiarchguard.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "management.endpoint.health.group.readiness.show-components=always")
@AutoConfigureMockMvc
class PlatformBootstrapIntegrationTest extends PostgresIntegrationTestSupport {

    private final MockMvc mockMvc;
    private final ListableBeanFactory beanFactory;

    @Autowired
    PlatformBootstrapIntegrationTest(MockMvc mockMvc, ListableBeanFactory beanFactory) {
        this.mockMvc = mockMvc;
        this.beanFactory = beanFactory;
    }

    @Test
    void startsApplicationContext() {
        assertThat(beanFactory.containsBean("archGuardPlatformApplication")).isTrue();
    }

    @Test
    void exposesLivenessWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void readinessIncludesDatabaseState() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void disablesUnapprovedActuatorEndpoints() {
        assertThat(beanFactory.getBeanNamesForType(InfoEndpoint.class)).isEmpty();
    }

    @Test
    void deniesUnconfiguredApplicationRoutes() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("authentication.required"))
            .andExpect(jsonPath("$.traceId").isString());
    }
}
