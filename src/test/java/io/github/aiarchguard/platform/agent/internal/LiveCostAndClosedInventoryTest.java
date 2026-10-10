package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveCostAndClosedInventoryTest {
    @Test void defaultInventoryNeverAcceptsCallerSuppliedDeclarations() {
        assertThat(new ClosedSyntheticBatchInventory().accepts(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64))).isFalse();
    }
    @Test void worstCaseAndRoundingMatchFrozenSnapshot() {
        assertThat(LiveCostPolicy.cost(new LiveTokenUsage(8000, 1500, 0, 9500, 0))).isEqualTo(LiveCostPolicy.RESERVATION);
        assertThat(LiveCostPolicy.cost(new LiveTokenUsage(1, 0, 1, 1, 0))).isOne();
        assertThat(LiveCostPolicy.cost(new LiveTokenUsage(1000, 100, 500, 1100, 0))).isEqualTo(273);
    }
    @Test void untrustedUsageNeverCreatesAnAccountingValue() {
        for (var usage : List.of(new LiveTokenUsage(-1, 0, 0, -1, 0), new LiveTokenUsage(8001, 0, 0, 8001, 0),
                new LiveTokenUsage(0, 1501, 0, 1501, 0), new LiveTokenUsage(1, 0, 2, 1, 0), new LiveTokenUsage(1, 1, 0, 3, 0),
                new LiveTokenUsage(1, 1, 0, 2, 1), new LiveTokenUsage(Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 0))) {
            assertThatThrownBy(() -> LiveCostPolicy.cost(usage)).isInstanceOf(AgentInvalidException.class);
        }
        assertThatThrownBy(() -> LiveCostPolicy.cost(null)).isInstanceOf(AgentInvalidException.class);
    }
}
