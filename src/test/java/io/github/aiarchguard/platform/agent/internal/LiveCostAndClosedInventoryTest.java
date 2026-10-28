package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 验证默认来源拒绝与冻结价格规则：调用方声明不能成为可信清单，用量必须满足范围和一致性。全部是本地确定性规则测试。
 */
class LiveCostAndClosedInventoryTest {
    // 默认清单永远拒绝，输入看似完整也不能自我授权。
    @Test void defaultInventoryNeverAcceptsCallerSuppliedDeclarations() {
        assertThat(new ClosedSyntheticBatchInventory().accepts(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64))).isFalse();
    }
    // 保守预留与实际取整应匹配冻结价格快照。
    @Test void worstCaseAndRoundingMatchFrozenSnapshot() {
        assertThat(LiveCostPolicy.cost(new LiveTokenUsage(8000, 1500, 0, 9500, 0))).isEqualTo(LiveCostPolicy.RESERVATION);
        assertThat(LiveCostPolicy.cost(new LiveTokenUsage(1, 0, 1, 1, 0))).isOne();
        assertThat(LiveCostPolicy.cost(new LiveTokenUsage(1000, 100, 500, 1100, 0))).isEqualTo(273);
    }
    // 非法用量不能进入表示可信费用依据的值对象。
    @Test void untrustedUsageNeverCreatesAnAccountingValue() {
        for (var usage : List.of(new LiveTokenUsage(-1, 0, 0, -1, 0), new LiveTokenUsage(8001, 0, 0, 8001, 0),
                new LiveTokenUsage(0, 1501, 0, 1501, 0), new LiveTokenUsage(1, 0, 2, 1, 0), new LiveTokenUsage(1, 1, 0, 3, 0),
                new LiveTokenUsage(1, 1, 0, 2, 1), new LiveTokenUsage(Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 0))) {
            assertThatThrownBy(() -> LiveCostPolicy.cost(usage)).isInstanceOf(AgentInvalidException.class);
        }
        assertThatThrownBy(() -> LiveCostPolicy.cost(null)).isInstanceOf(AgentInvalidException.class);
    }
}
