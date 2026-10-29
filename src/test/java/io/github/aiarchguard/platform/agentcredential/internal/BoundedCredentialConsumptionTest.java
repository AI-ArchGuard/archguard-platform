package io.github.aiarchguard.platform.agentcredential.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 真实锁竞争的有界消费回归：原期限、中断、版本匹配、异常清理及零迟到回调。 */
class BoundedCredentialConsumptionTest {
    private static final long FIVE_SECONDS = Duration.ofSeconds(5).toNanos();
    private static final String SYNTHETIC = "synthetic-bounded-consumption-only";
    @TempDir Path root;
    EncryptedCredentialStore store;
    UUID version;

    @BeforeEach void prepare() throws Exception {
        Path directory = Files.createDirectory(root.resolve("encrypted"));
        Path master = Files.write(root.resolve("synthetic-master"), new byte[32]);
        PrivateCredentialFiles.restrict(directory, true); PrivateCredentialFiles.restrict(master, false);
        store = new EncryptedCredentialStore(directory, master, UUID.randomUUID());
        version = store.write(SYNTHETIC, Instant.parse("2026-10-15T00:00:00Z")).credentialVersion();
    }

    /** 当前版本只回调一次；所有同槽位重入须在读取嵌套期限前拒绝。 */
    @Test void currentVersionIsConsumedOnceAndCannotReenterAnySharedStoreOperation() {
        var calls = new AtomicInteger(); var nestedSupplierReads = new AtomicInteger();
        store.consume(version, () -> FIVE_SECONDS, key -> {
            assertThat(key).isEqualTo(SYNTHETIC); calls.incrementAndGet();
            assertUnavailable(store::status);
            assertUnavailable(() -> store.write(SYNTHETIC + "-replacement", Instant.now()));
            assertUnavailable(store::delete);
            assertUnavailable(() -> store.consume(version, ignored -> calls.incrementAndGet()));
            assertUnavailable(() -> store.consume(version, () -> { nestedSupplierReads.incrementAndGet(); return FIVE_SECONDS; }, ignored -> calls.incrementAndGet()));
        });
        assertThat(calls).hasValue(1); assertThat(nestedSupplierReads).hasValue(0);
        assertThat(store.status().credentialVersion()).isEqualTo(version);
        store.consume(version, () -> FIVE_SECONDS, key -> assertThat(key).isEqualTo(SYNTHETIC));
    }

    /** 实际争锁后超时须在原持有者释放前退出，释放后也不能迟到执行。 */
    @Test void realLockWaitTimesOutWhileTheOwnerStillHoldsTheSlotAndCannotRunLate() throws Exception {
        var calls = new AtomicInteger(); var failure = new AtomicReference<Throwable>(); var done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try { store.consume(version, () -> Duration.ofMillis(300).toNanos(), key -> calls.incrementAndGet()); }
            catch (Throwable unavailable) { failure.set(unavailable); }
            finally { done.countDown(); }
        }, "synthetic-bounded-timeout");
        try (var held = holdSlot()) {
            waiter.start();
            awaitBlocked(waiter);
            assertThat(done.await(2, TimeUnit.SECONDS)).as("waiter exits without the holder releasing its slot").isTrue();
            assertThat(held.release.getCount()).isOne();
            waiter.join(2_000); assertThat(waiter.isAlive()).isFalse();
            assertUnavailable(failure.get()); assertThat(calls).hasValue(0);
        } finally { waiter.join(2_000); }
        assertThat(calls).hasValue(0);
        store.consume(version, () -> FIVE_SECONDS, key -> assertThat(key).isEqualTo(SYNTHETIC));
    }

    /** 中断真实等待者须提前退出、保留中断标志且始终零回调。 */
    @Test void interruptingABoundedLockWaitActuallyExitsAndPreservesTheFlagWithoutALateCallback() throws Exception {
        var calls = new AtomicInteger(); var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean(); var done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try { store.consume(version, () -> FIVE_SECONDS, key -> calls.incrementAndGet()); }
            catch (Throwable unavailable) { failure.set(unavailable); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); done.countDown(); }
        }, "synthetic-bounded-interrupt");
        try (var held = holdSlot()) {
            waiter.start(); awaitBlocked(waiter); waiter.interrupt();
            assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(held.release.getCount()).isOne();
            waiter.join(2_000); assertThat(waiter.isAlive()).isFalse();
            assertUnavailable(failure.get()); assertThat(interrupted.get()).isTrue(); assertThat(calls).hasValue(0);
        } finally { waiter.interrupt(); waiter.join(2_000); }
        assertThat(calls).hasValue(0);
        assertThat(store.status().credentialVersion()).isEqualTo(version);
    }

    /** 进入前已中断立即拒绝；清理测试线程标志后槽位仍可用。 */
    @Test void preInterruptedBoundedConsumptionNeverInvokesCallbackAndLeavesTheSlotUsable() {
        var calls = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            assertUnavailable(() -> store.consume(version, () -> FIVE_SECONDS, key -> calls.incrementAndGet()));
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); assertThat(calls).hasValue(0);
        } finally { Thread.interrupted(); }
        assertThat(store.status().credentialVersion()).isEqualTo(version);
    }

    /** 只有当前新版本能消费一次，未知、旧和删除版本均不得回调。 */
    @Test void boundedConsumptionCannotUseMissingStaleReplacedOrDeletedVersions() {
        var calls = new AtomicInteger();
        assertUnavailable(() -> store.consume(UUID.randomUUID(), () -> FIVE_SECONDS, key -> calls.incrementAndGet()));
        var replacement = store.write(SYNTHETIC + "-replacement", Instant.parse("2026-10-15T00:00:01Z"));
        assertUnavailable(() -> store.consume(version, () -> FIVE_SECONDS, key -> calls.incrementAndGet()));
        store.consume(replacement.credentialVersion(), () -> FIVE_SECONDS, key -> {
            assertThat(key).isEqualTo(SYNTHETIC + "-replacement"); calls.incrementAndGet();
        });
        store.delete();
        assertUnavailable(() -> store.consume(replacement.credentialVersion(), () -> FIVE_SECONDS, key -> calls.incrementAndGet()));
        assertThat(calls).hasValue(1); assertThat(store.status().configured()).isFalse();
    }

    /** 空、非正、超过 30 秒或抛错的预算，以及空版本/回调都必须零消费。 */
    @Test void nullZeroNegativeExcessiveAndThrowingBudgetsNeverInvokeCallbacks() {
        var calls = new AtomicInteger();
        assertUnavailable(() -> store.consume(version, (LongSupplier) null, key -> calls.incrementAndGet()));
        for (long invalid : new long[] {0, -1, Duration.ofSeconds(30).toNanos() + 1, Long.MAX_VALUE}) {
            assertUnavailable(() -> store.consume(version, () -> invalid, key -> calls.incrementAndGet()));
        }
        assertUnavailable(() -> store.consume(version, () -> { throw new IllegalStateException(SYNTHETIC + root); }, key -> calls.incrementAndGet()));
        assertUnavailable(() -> store.consume(null, () -> FIVE_SECONDS, key -> calls.incrementAndGet()));
        assertUnavailable(() -> store.consume(version, () -> FIVE_SECONDS, null));
        assertThat(calls).hasValue(0); assertThat(store.status().credentialVersion()).isEqualTo(version);
    }

    /** 解密流程内期限到期不能进入回调，失败后锁仍能再次获取。 */
    @Test void expiringTheBudgetBeforeTheCallbackPreventsConsumptionAndReleasesTheLock() {
        var reads = new AtomicInteger(); var calls = new AtomicInteger();
        LongSupplier expires = () -> reads.incrementAndGet() >= 5 ? 0 : FIVE_SECONDS;
        assertUnavailable(() -> store.consume(version, expires, key -> calls.incrementAndGet()));
        assertThat(calls).hasValue(0);
        store.consume(version, () -> FIVE_SECONDS, key -> assertThat(key).isEqualTo(SYNTHETIC));
    }

    /** 回调已执行后到期/中断会安全失败，但必须清理重入标志，不撤回既有副作用。 */
    @Test void expiryOrInterruptionDuringCallbackIsSanitizedAndAlwaysClearsTheReentryGuard() {
        var remaining = new AtomicLong(FIVE_SECONDS); var calls = new AtomicInteger();
        assertUnavailable(() -> store.consume(version, remaining::get, key -> {
            calls.incrementAndGet(); remaining.set(0);
        }));
        assertThat(calls).hasValue(1); assertThat(store.status().credentialVersion()).isEqualTo(version);
        try {
            assertUnavailable(() -> store.consume(version, () -> FIVE_SECONDS, key -> Thread.currentThread().interrupt()));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        store.consume(version, () -> FIVE_SECONDS, key -> assertThat(key).isEqualTo(SYNTHETIC));
    }

    /** 回调异常不传播私有消息或 cause，后续消费及删除仍正常。 */
    @Test void callbackExceptionHasNoPrivateMessageOrCauseAndDoesNotPoisonTheSlot() {
        assertUnavailable(() -> store.consume(version, () -> FIVE_SECONDS, key -> {
            throw new IllegalStateException(key + root, new IllegalArgumentException(key));
        }));
        store.consume(version, () -> FIVE_SECONDS, key -> assertThat(key).isEqualTo(SYNTHETIC));
        assertThat(store.delete().configured()).isFalse();
    }

    /** 即使供应器增大剩余值，本地实际耗时仍强制原始预算到期。 */
    @Test void deadlineCannotBeExtendedByAConstantOrIncreasingSupplier() {
        var supplied = new AtomicLong(Duration.ofMillis(80).toNanos());
        var deadline = new CredentialDeadline(supplied::get);
        long before = deadline.remainingNanos(); supplied.set(FIVE_SECONDS);
        assertThat(deadline.remainingNanos()).isLessThanOrEqualTo(before);
        long until = System.nanoTime() + Duration.ofMillis(120).toNanos();
        while (System.nanoTime() < until) LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        assertUnavailable(deadline::remainingNanos);
    }

    /** 首次供应器读取本身也计时；后续非法值或异常不能绕过期限。 */
    @Test void firstSupplierReadTimeIsChargedAndSubsequentInvalidOrThrowingReadsFailClosed() {
        long initial = Duration.ofMillis(30).toNanos();
        var reads = new AtomicInteger();
        assertUnavailable(() -> new CredentialDeadline(() -> {
            if (reads.incrementAndGet() == 1) {
                long until = System.nanoTime() + Duration.ofMillis(50).toNanos();
                while (System.nanoTime() < until) LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            }
            return initial;
        }));
        var supplied = new AtomicLong(FIVE_SECONDS); var deadline = new CredentialDeadline(supplied::get);
        supplied.set(Duration.ofSeconds(31).toNanos()); assertUnavailable(deadline::remainingNanos);
        var fail = new AtomicBoolean(); var throwing = new CredentialDeadline(() -> {
            if (fail.get()) throw new IllegalStateException(SYNTHETIC + root); return FIVE_SECONDS;
        });
        fail.set(true); assertUnavailable(throwing::remainingNanos);
    }

    private HeldSlot holdSlot() throws Exception { return new HeldSlot(); }

    /** 用兼容消费持住共享槽位，关闭时释放并回收线程，供有界等待者竞争。 */
    private final class HeldSlot implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread holder = new Thread(() -> {
            try {
                store.consume(version, key -> {
                    entered.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                });
            } catch (Throwable unavailable) { failure.set(unavailable); }
        }, "synthetic-bounded-holder");

        HeldSlot() throws Exception {
            holder.start();
            if (!entered.await(3, TimeUnit.SECONDS)) {
                release.countDown(); holder.join(2_000); throw new AssertionError("Synthetic holder failed to enter");
            }
        }
        @Override public void close() throws Exception {
            release.countDown(); holder.join(3_000);
            assertThat(holder.isAlive()).isFalse(); assertThat(failure.get()).isNull();
        }
    }

    /** 观察到真实等待状态后才继续断言，避免把尚未调度误判为锁等待。 */
    private static void awaitBlocked(Thread thread) {
        long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < until && thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING) {
            if (thread.getState() == Thread.State.TERMINATED) break;
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
        assertThat(thread.getState()).as("bounded consumer actually waits for the held credential slot")
            .isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING);
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(CredentialFailure.class).hasMessage("credential.unavailable").hasNoCause();
    }
    private static void assertUnavailable(Throwable failure) {
        assertThat(failure).isInstanceOf(CredentialFailure.class).hasMessage("credential.unavailable").hasNoCause();
    }
}
