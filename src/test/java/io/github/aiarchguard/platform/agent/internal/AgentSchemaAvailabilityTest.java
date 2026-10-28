package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import org.junit.jupiter.api.Test;

/**
 * 验证 Agent 独立迁移的可用性状态：失败只关闭 Agent，成功才开放其 Schema 使用。迁移由本地可控动作模拟。
 */
class AgentSchemaAvailabilityTest {
    // 迁移异常转化为 Agent 不可用，不把领域功能误判为已就绪。
    @Test void migrationFailureLeavesOnlyAgentUnavailable() {
        AgentSchemaAvailability availability = new AgentSchemaAvailability(() -> {
            throw new IllegalStateException("synthetic migration failure");
        });
        availability.migrateOnReady();
        assertThat(availability.ready()).isFalse();
        assertThatThrownBy(availability::requireReady).isInstanceOf(AgentUnavailableException.class);
    }

    // 只有迁移动作实际成功才允许后续 Agent 操作。
    @Test void successfulMigrationEnablesAgent() {
        AgentSchemaAvailability availability = new AgentSchemaAvailability(() -> {});
        availability.migrateOnReady();
        assertThat(availability.ready()).isTrue();
    }
}
