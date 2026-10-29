package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import org.junit.jupiter.api.Test;

/**
 * 验证冻结价格快照的保守预留与整数费用，区分旧策略和上下文策略。金额源于固定规则与合成用量，并非真实供应商账单。
 */
class DeepSeekPriceSnapshotTest {
    // 预留按高峰未命中上限，实际费用汇总后仅做一次向上取整。
    @Test void reservesPeakCacheMissCapsAndRoundsSettlementOnce() {
        assertThat(DeepSeekPriceSnapshot.maxReservationMicrousd()).isEqualTo(4200);
        assertThat(DeepSeekPriceSnapshot.peakCostMicrousd(100, 20, 40)).isEqualTo(43);
        assertThat(DeepSeekPriceSnapshot.peakCostMicrousd(1, 1, 0)).isEqualTo(2);
        assertThat(DeepSeekPriceSnapshot.peakCostMicrousd(8000, 1500, 0)).isEqualTo(4200);
        assertThat(DeepSeekPriceSnapshot.peakCostMicrousd(8000, 1500, 8000)).isEqualTo(1848);
        assertThat(DeepSeekPriceSnapshot.peakCostMicrousd(0, 0, 0)).isZero();
    }

    @Test void rejectsBadUsageAndUnrecognizedOrExpiredSnapshot() {
        for (int[] usage : new int[][] {{-1,0,0}, {8001,0,0}, {0,1501,0}, {1,1,2}, {1,1,-1}}) {
            assertThatThrownBy(() -> DeepSeekPriceSnapshot.peakCostMicrousd(usage[0], usage[1], usage[2]))
                .isInstanceOf(IllegalArgumentException.class);
        }
        Instant now = Instant.parse("2026-10-06T00:00:00Z");
        assertThat(DeepSeekPriceSnapshot.valid(DeepSeekPriceSnapshot.VERSION, now.plusSeconds(60), now)).isTrue();
        assertThat(DeepSeekPriceSnapshot.valid("unknown", now.plusSeconds(60), now)).isFalse();
        assertThat(DeepSeekPriceSnapshot.valid(DeepSeekPriceSnapshot.VERSION, now, now)).isFalse();
    }

    // 上下文版本使用独立预留，超观察阈值的可信用量仍须计费。
    @Test void contextSnapshotUsesItsOwnReservationAndDoesNotDiscardOverThresholdUsage() {
        assertThat(DeepSeekPriceSnapshot.maxReservationMicrousd(LiveCostPolicy.VERSION)).isEqualTo(4200);
        assertThat(DeepSeekPriceSnapshot.maxReservationMicrousd(LiveCostPolicy.CONTEXT_VERSION)).isEqualTo(316373);
        assertThat(DeepSeekPriceSnapshot.peakCostMicrousd(LiveCostPolicy.CONTEXT_VERSION, 8001, 1500, 0)).isEqualTo(4201);
        assertThat(DeepSeekPriceSnapshot.peakCostMicrousd(LiveCostPolicy.CONTEXT_VERSION, 1_048_576, 1500, 0)).isEqualTo(316373);
        assertThat(DeepSeekPriceSnapshot.peakCostMicrousd(LiveCostPolicy.CONTEXT_VERSION, 1_048_576, 1500, 1_048_576)).isEqualTo(8092);
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        assertThat(DeepSeekPriceSnapshot.valid(LiveCostPolicy.CONTEXT_VERSION, now.plusSeconds(60), now)).isTrue();
        assertThat(DeepSeekPriceSnapshot.valid(LiveCostPolicy.CONTEXT_VERSION, now, now)).isFalse();
    }

    // 未知目录与溢出不得回退至其他已知策略。
    @Test void versionedSnapshotRejectsUnknownCatalogsAndOverflowWithoutFallback() {
        for (String unknown : new String[] {null, "", "unknown"}) {
            assertThatThrownBy(() -> DeepSeekPriceSnapshot.maxReservationMicrousd(unknown)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
            assertThatThrownBy(() -> DeepSeekPriceSnapshot.peakCostMicrousd(unknown, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        }
        assertThatThrownBy(() -> DeepSeekPriceSnapshot.peakCostMicrousd(LiveCostPolicy.CONTEXT_VERSION, Integer.MAX_VALUE, Integer.MAX_VALUE, 0))
            .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }
}
