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

/**
 * 验证完整 Spring 上下文、健康探针及默认路由安全边界。使用容器 PostgreSQL 读取 readiness 状态，不证明生产部署或外部依赖已经就绪。
 */
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
    // 上下文启动只证明当前测试配置能够装配。
    void startsApplicationContext() {
        assertThat(beanFactory.containsBean("archGuardPlatformApplication")).isTrue();
    }

    @Test
    // 存活探针对外可用，但不得公开组件细节。
    void exposesLivenessWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    // 就绪检查实际包含数据库，避免只把应用进程存在视为业务就绪。
    void readinessIncludesDatabaseState() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    // 仅批准的运维入口开放，其他 Actuator 功能不应被顺带暴露。
    void disablesUnapprovedActuatorEndpoints() {
        assertThat(beanFactory.getBeanNamesForType(InfoEndpoint.class)).isEmpty();
    }

    @Test
    // 没有业务认证配置时默认拒绝应用路由。
    void deniesUnconfiguredApplicationRoutes() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("authentication.required"))
            .andExpect(jsonPath("$.traceId").isString());
    }
}
