package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 验证执行预算只能收紧：本地时钟和外部剩余值共同限制期限，任何后续增长都不能续期。使用可控时钟，不度量真实网络或数据库耗时。
 */
class LiveExecutionBudgetTest {
    // 上游返回常量或变大，仍受第一次确定的本地期限限制。
    @Test void constantOrIncreasingUpstreamCannotRenewAnEarlierLimit() {
        var nanos = new AtomicLong(); var upstream = new AtomicLong(Duration.ofSeconds(3).toNanos());
        var budget = new LiveExecutionBudget(nanos::get, upstream::get);
        assertThat(budget.requireRemaining()).isEqualTo(Duration.ofSeconds(3));
        upstream.set(Duration.ofSeconds(30).toNanos()); nanos.set(Duration.ofSeconds(2).toNanos());
        assertThat(budget.requireRemaining()).isEqualTo(Duration.ofSeconds(1));
        nanos.set(Duration.ofSeconds(3).toNanos());
        assertThatThrownBy(budget::requireRemaining).hasMessage("MODEL_TIMEOUT").hasNoCause();
    }

    // 曾经收紧过的外部上限不可在随后放宽。
    @Test void aTightenedExternalLimitCannotBeRelaxedLater() {
        var nanos = new AtomicLong(); var upstream = new AtomicLong(Duration.ofSeconds(30).toNanos());
        var budget = new LiveExecutionBudget(nanos::get, upstream::get);
        upstream.set(1); assertThat(budget.getAsLong()).isOne();
        upstream.set(Duration.ofSeconds(30).toNanos()); assertThat(budget.getAsLong()).isOne();
        upstream.set(0); assertThat(budget.getAsLong()).isZero();
        upstream.set(Duration.ofSeconds(30).toNanos()); assertThat(budget.getAsLong()).isZero();
    }

    // 中断或时钟倒退立即拒绝，并保留中断状态。
    @Test void interruptionAndBackwardsClockFailWithoutClearingInterruption() {
        var nanos = new AtomicLong(); var budget = new LiveExecutionBudget(nanos::get);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(budget::requireRemaining).hasMessage("MODEL_TIMEOUT");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(budget.getAsLong()).as("cleanup cannot restore send eligibility").isZero();
        var backwards = new LiveExecutionBudget(nanos::get);
        nanos.set(-1); assertThat(backwards.getAsLong()).isZero();
    }

    // 异常或超范围供应器不能泄漏原因或创建无界预算。
    @Test void oversizedOrThrowingSupplierIsSanitized() {
        assertThatThrownBy(() -> new LiveExecutionBudget(() -> 0, () -> Duration.ofSeconds(31).toNanos()))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThatThrownBy(() -> new LiveExecutionBudget(() -> 0, () -> {
            throw new IllegalStateException("private budget detail");
        })).isInstanceOf(AgentUnavailableException.class).hasNoCause().hasMessage("Agent storage is unavailable");
    }
}
