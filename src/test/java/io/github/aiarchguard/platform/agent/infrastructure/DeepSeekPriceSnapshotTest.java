package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeepSeekPriceSnapshotTest {
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
}
