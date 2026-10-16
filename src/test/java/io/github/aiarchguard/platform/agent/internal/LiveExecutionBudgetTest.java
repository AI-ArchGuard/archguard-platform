package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LiveExecutionBudgetTest {
    @Test void constantOrIncreasingUpstreamCannotRenewAnEarlierLimit() {
        var nanos = new AtomicLong(); var upstream = new AtomicLong(Duration.ofSeconds(3).toNanos());
        var budget = new LiveExecutionBudget(nanos::get, upstream::get);
        assertThat(budget.requireRemaining()).isEqualTo(Duration.ofSeconds(3));
        upstream.set(Duration.ofSeconds(30).toNanos()); nanos.set(Duration.ofSeconds(2).toNanos());
        assertThat(budget.requireRemaining()).isEqualTo(Duration.ofSeconds(1));
        nanos.set(Duration.ofSeconds(3).toNanos());
        assertThatThrownBy(budget::requireRemaining).hasMessage("MODEL_TIMEOUT").hasNoCause();
    }

    @Test void aTightenedExternalLimitCannotBeRelaxedLater() {
        var nanos = new AtomicLong(); var upstream = new AtomicLong(Duration.ofSeconds(30).toNanos());
        var budget = new LiveExecutionBudget(nanos::get, upstream::get);
        upstream.set(1); assertThat(budget.getAsLong()).isOne();
        upstream.set(Duration.ofSeconds(30).toNanos()); assertThat(budget.getAsLong()).isOne();
        upstream.set(0); assertThat(budget.getAsLong()).isZero();
        upstream.set(Duration.ofSeconds(30).toNanos()); assertThat(budget.getAsLong()).isZero();
    }

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

    @Test void oversizedOrThrowingSupplierIsSanitized() {
        assertThatThrownBy(() -> new LiveExecutionBudget(() -> 0, () -> Duration.ofSeconds(31).toNanos()))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThatThrownBy(() -> new LiveExecutionBudget(() -> 0, () -> {
            throw new IllegalStateException("private budget detail");
        })).isInstanceOf(AgentUnavailableException.class).hasNoCause().hasMessage("Agent storage is unavailable");
    }
}
