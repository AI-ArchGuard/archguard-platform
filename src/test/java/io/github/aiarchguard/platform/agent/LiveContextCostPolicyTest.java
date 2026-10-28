package io.github.aiarchguard.platform.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证独立上下文费用策略的版本、保守预留与合法用量范围，同时锁定旧默认策略。所有金额由合成用量确定性计算。
 */
class LiveContextCostPolicyTest {
    // 旧默认与显式旧版本结果保持一致，新增策略不能改变历史费用。
    @Test void legacyDefaultAndExplicitPolicyRemainExactlyFrozen() {
        assertThat(LiveCostPolicy.VERSION).isEqualTo("deepseek-flash-peak-usd-2026-10-06");
        assertThat(LiveCostPolicy.RESERVATION).isEqualTo(4200);
        assertThat(LiveCostPolicy.reservation(LiveCostPolicy.VERSION)).isEqualTo(4200);
        assertThat(LiveCostPolicy.modelProfile(LiveCostPolicy.VERSION)).isEqualTo("deepseek-personal-0.1.0");
        assertThat(LiveCostPolicy.supported(LiveCostPolicy.VERSION)).isTrue();
        assertThat(LiveCostPolicy.isContext(LiveCostPolicy.VERSION)).isFalse();
        var oldMaximum = new LiveTokenUsage(8000, 1500, 0, 9500, 0);
        assertThat(LiveCostPolicy.cost(oldMaximum)).isEqualTo(4200);
        assertThat(LiveCostPolicy.cost(LiveCostPolicy.VERSION, oldMaximum)).isEqualTo(4200);
        var above = new LiveTokenUsage(8001, 0, 0, 8001, 0);
        assertThatThrownBy(() -> LiveCostPolicy.cost(above)).isInstanceOf(AgentInvalidException.class);
        assertThatThrownBy(() -> LiveCostPolicy.cost(LiveCostPolicy.VERSION, above)).isInstanceOf(AgentInvalidException.class);
    }

    // 上下文策略有独立版本与按完整上界估算的保守预留。
    @Test void contextPolicyHasAnIndependentImmutableVersionAndConservativeReservation() {
        assertThat(LiveCostPolicy.CONTEXT_VERSION).isEqualTo("deepseek-flash-context-peak-usd-2026-10-15");
        assertThat(LiveCostPolicy.CONTEXT_MODEL_PROFILE).isEqualTo("deepseek-personal-0.2.0");
        assertThat(LiveCostPolicy.CONTEXT_INPUT_LIMIT).isEqualTo(1_048_576);
        assertThat(LiveCostPolicy.POSTCALL_INPUT_THRESHOLD).isEqualTo(8000);
        assertThat(LiveCostPolicy.CONTEXT_RESERVATION).isEqualTo(316373);
        assertThat(LiveCostPolicy.PER_REQUEST_LIMIT).isEqualTo(350000);
        assertThat(LiveCostPolicy.BATCH_LIMIT).isEqualTo(700000);
        assertThat(LiveCostPolicy.supported(LiveCostPolicy.CONTEXT_VERSION)).isTrue();
        assertThat(LiveCostPolicy.isContext(LiveCostPolicy.CONTEXT_VERSION)).isTrue();
        assertThat(LiveCostPolicy.modelProfile(LiveCostPolicy.CONTEXT_VERSION)).isEqualTo(LiveCostPolicy.CONTEXT_MODEL_PROFILE);
        assertThat(LiveCostPolicy.reservation(LiveCostPolicy.CONTEXT_VERSION)).isEqualTo(316373);
        assertThat(LiveCostPolicy.CONTEXT_RESERVATION).isLessThanOrEqualTo(LiveCostPolicy.PER_REQUEST_LIMIT);
        assertThat(2 * LiveCostPolicy.CONTEXT_RESERVATION).isEqualTo(632746).isLessThanOrEqualTo(LiveCostPolicy.BATCH_LIMIT);
    }

    // 超过观察阈值仍可有可信费用，不能直接降格为 UNKNOWN。
    @Test void inputAboveTheObservationThresholdRemainsBillableInsteadOfBecomingUnknown() {
        assertThat(LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(8000, 0, 0, 8000, 0))).isEqualTo(2400);
        assertThat(LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(8001, 0, 0, 8001, 0))).isEqualTo(2401);
        assertThat(LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(8001, 1500, 0, 9501, 0))).isEqualTo(4201);
    }

    @Test void fullContextInputPlusSeparateMaximumOutputRoundsUpToTheFullReservation() {
        var usage = new LiveTokenUsage(1_048_576, 1500, 0, 1_050_076, 0);
        assertThat(LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, usage)).isEqualTo(316373);
        assertThat(LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, usage)).isEqualTo(LiveCostPolicy.CONTEXT_RESERVATION);
        assertThat(LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(1_048_576, 1500, 1_048_576, 1_050_076, 0)))
            .isEqualTo(8092);
    }

    // 按获批高峰费率聚合后统一取整，避免逐段取整改变总价。
    @Test void contextCostUsesTheSamePeakRatesAndOnlyOneFinalRounding() {
        assertThat(LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(100, 20, 40, 120, 0))).isEqualTo(43);
        assertThat(LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(1, 0, 1, 1, 0))).isOne();
        assertThat(LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(0, 0, 0, 0, 0))).isZero();
    }

    // 未知版本不能被猜测成旧价格或模型配置。
    @Test void unknownVersionsCannotSelectLegacyPricesOrProfiles() {
        for (String unknown : new String[] {null, "", "unknown", "deepseek-flash-context-peak-usd-2026-10-16"}) {
            assertThat(LiveCostPolicy.supported(unknown)).isFalse();
            assertInvalid(() -> LiveCostPolicy.cost(unknown, new LiveTokenUsage(1, 0, 0, 1, 0)));
            assertInvalid(() -> LiveCostPolicy.reservation(unknown));
            assertInvalid(() -> LiveCostPolicy.modelProfile(unknown));
            assertInvalid(() -> LiveCostPolicy.isContext(unknown));
        }
    }

    @Test void malformedAndOutOfRangeContextUsageNeverBecomesAnAccountingValue() {
        for (var usage : List.of(new LiveTokenUsage(-1, 0, 0, -1, 0), new LiveTokenUsage(1_048_577, 0, 0, 1_048_577, 0),
                new LiveTokenUsage(0, -1, 0, -1, 0), new LiveTokenUsage(0, 1501, 0, 1501, 0),
                new LiveTokenUsage(1, 0, -1, 1, 0), new LiveTokenUsage(1, 0, 2, 1, 0),
                new LiveTokenUsage(1, 1, 0, 1, 0), new LiveTokenUsage(1, 1, 0, -1, 0),
                new LiveTokenUsage(1, 1, 0, 2, 1), new LiveTokenUsage(1, 1, 0, 2, -1),
                new LiveTokenUsage(Integer.MAX_VALUE, 1500, 0, Integer.MIN_VALUE + 1499, 0),
                new LiveTokenUsage(1_048_576, Integer.MAX_VALUE, 0, Integer.MIN_VALUE, 0))) {
            assertInvalid(() -> LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, usage));
        }
        assertInvalid(() -> LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, null));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(AgentInvalidException.class).hasNoCause();
    }
}
