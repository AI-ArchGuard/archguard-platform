package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import org.junit.jupiter.api.Test;

class AgentSchemaAvailabilityTest {
    @Test void migrationFailureLeavesOnlyAgentUnavailable() {
        AgentSchemaAvailability availability = new AgentSchemaAvailability(() -> {
            throw new IllegalStateException("synthetic migration failure");
        });
        availability.migrateOnReady();
        assertThat(availability.ready()).isFalse();
        assertThatThrownBy(availability::requireReady).isInstanceOf(AgentUnavailableException.class);
    }

    @Test void successfulMigrationEnablesAgent() {
        AgentSchemaAvailability availability = new AgentSchemaAvailability(() -> {});
        availability.migrateOnReady();
        assertThat(availability.ready()).isTrue();
    }
}
