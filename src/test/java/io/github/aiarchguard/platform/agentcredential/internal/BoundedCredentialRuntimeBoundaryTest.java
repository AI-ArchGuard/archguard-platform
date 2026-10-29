package io.github.aiarchguard.platform.agentcredential.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.identity.CurrentActor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** 验证有界元数据和审计跨模块调用保留原预算，并与最终消费共享同一槽位。 */
class BoundedCredentialRuntimeBoundaryTest {
    private static final long BUDGET = Duration.ofSeconds(5).toNanos();
    private static final String KEY = "synthetic-runtime-boundary-key-only";
    @TempDir Path root;

    /** 旧元数据/审计实现不支持有界重载时直接失败，禁止调用无界旧方法。 */
    @Test void unsupportedMetadataAndAuditDoNotCallTheirLegacyMethods() {
        var calls = new AtomicInteger();
        CredentialVersionSource source = () -> { calls.incrementAndGet(); return null; };
        AuditRecorder audit = event -> calls.incrementAndGet();
        assertThatThrownBy(() -> source.current(() -> BUDGET)).isInstanceOf(CredentialFailure.class).hasNoCause();
        assertThatThrownBy(() -> audit.record(null, () -> BUDGET)).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThat(calls).hasValue(0);
    }

    /** 有界元数据读取观察同一管理存储的当前、替换及删除状态。 */
    @Test void boundedMetadataReadsCurrentVersionAndObservesReplacementAndDeletion() throws Exception {
        var fixture = fixture();
        assertThat(fixture.versions.current(() -> BUDGET).credentialVersion()).isEqualTo(fixture.version);
        var replacement = fixture.service.write("synthetic-runtime-replacement-key");
        assertThat(fixture.versions.current(() -> BUDGET).credentialVersion()).isEqualTo(replacement.credentialVersion());
        fixture.service.delete();
        assertThat(fixture.versions.current(() -> BUDGET).configured()).isFalse();
    }

    /** 有界消费只使用有界审计入口，传入不大于原预算的正剩余时间。 */
    @Test void consumptionUsesOnlyTheExplicitBoundedAuditAndPassesItsOriginalRemainingBudget() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger();
        fixture.source.consume(fixture.version, () -> BUDGET, key -> {
            assertThat(key).isEqualTo(KEY); calls.incrementAndGet();
        });
        assertThat(calls).hasValue(1); assertThat(fixture.boundedAudits).hasValue(1);
        assertThat(fixture.legacyAudits).hasValue(0);
        assertThat(fixture.observedBudget.get()).isBetween(1L, BUDGET);
    }

    /** 有界意图审计失败必须零消费，不能改走旧审计挽救请求。 */
    @Test void failedBoundedAuditPreventsConsumptionWithoutFallingBackToLegacy() throws Exception {
        var fixture = fixture(); fixture.failBounded.set(true); var calls = new AtomicInteger();
        assertThatThrownBy(() -> fixture.source.consume(fixture.version, () -> BUDGET, key -> calls.incrementAndGet()))
            .isInstanceOf(CredentialFailure.class).hasNoCause();
        assertThat(calls).hasValue(0); assertThat(fixture.boundedAudits).hasValue(1);
        assertThat(fixture.legacyAudits).hasValue(0);
    }

    /** 元数据读取也拒绝非法预算并保留已有中断，清理后仍可读取当前版本。 */
    @Test void metadataRejectsInvalidBudgetAndKeepsAnExistingInterrupt() throws Exception {
        var fixture = fixture();
        for (long value : new long[]{0, -1, Duration.ofSeconds(30).toNanos() + 1}) {
            assertThatThrownBy(() -> fixture.versions.current(() -> value)).isInstanceOf(CredentialFailure.class).hasNoCause();
        }
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> fixture.versions.current(() -> BUDGET)).isInstanceOf(CredentialFailure.class).hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(fixture.versions.current(() -> BUDGET).credentialVersion()).isEqualTo(fixture.version);
    }

    /** 消费占槽时，元数据等待必须在持有者释放前按原期限退出。 */
    @Test void metadataUsesTheSameSlotAndTimesOutBeforeItsHolderReleases() throws Exception { blockedMetadata(false); }

    /** 读取元数据争用同一槽位，新增中断也能提前退出并保留标志。 */
    @Test void metadataUsesTheSameSlotAndPreservesNewCancellationBeforeItsHolderReleases() throws Exception { blockedMetadata(true); }

    /** 持锁消费中重入元数据必须在期限供应器和审计访问前拒绝。 */
    @Test void metadataReentryIsRejectedBeforeReadingTheDeadlineOrAccessingAudit() throws Exception {
        var fixture = fixture(); var budgetReads = new AtomicInteger();
        fixture.source.consume(fixture.version, key -> {
            int auditCount = fixture.legacyAudits.get() + fixture.boundedAudits.get();
            assertThatThrownBy(() -> fixture.versions.current(() -> { budgetReads.incrementAndGet(); return BUDGET; }))
                .isInstanceOf(CredentialFailure.class).hasNoCause();
            assertThat(budgetReads).hasValue(0);
            assertThat(fixture.legacyAudits.get() + fixture.boundedAudits.get()).isEqualTo(auditCount);
            assertThat(key).isEqualTo(KEY);
        });
    }

    /** 用持锁消费和独立读取线程验证真实等待，finally 必须释放并回收两个线程。 */
    private void blockedMetadata(boolean interrupt) throws Exception {
        var fixture = fixture(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var done = new CountDownLatch(1); var budgetRead = new CountDownLatch(1);
        var holderFailure = new AtomicReference<Throwable>(); var waiterFailure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread holder = new Thread(() -> {
            try { fixture.source.consume(fixture.version, key -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Synthetic slot release timed out"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }); } catch (Throwable failure) { holderFailure.set(failure); }
        }, "synthetic-runtime-metadata-holder");
        Thread waiter = new Thread(() -> {
            long end = System.nanoTime() + (interrupt ? BUDGET : TimeUnit.MILLISECONDS.toNanos(400));
            try { fixture.versions.current(() -> { budgetRead.countDown(); return end - System.nanoTime(); }); }
            catch (Throwable failure) { waiterFailure.set(failure); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); done.countDown(); }
        }, "synthetic-runtime-metadata-waiter");
        holder.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); waiter.start();
            assertThat(budgetRead.await(2, TimeUnit.SECONDS)).as("metadata must use the explicit original budget").isTrue();
            if (interrupt) {
                long observing = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (waiter.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < observing && done.getCount() != 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                assertThat(waiter.getState()).as("observe the real interruptible slot wait").isEqualTo(Thread.State.TIMED_WAITING);
                waiter.interrupt();
            }
            assertThat(done.await(2, TimeUnit.SECONDS)).as("waiter exits before slot owner releases").isTrue();
            assertThat(release.getCount()).isOne(); assertThat(holder.isAlive()).isTrue();
        } finally { release.countDown(); holder.join(5000); waiter.join(5000); }
        assertThat(holder.isAlive()).isFalse(); assertThat(waiter.isAlive()).isFalse(); assertThat(holderFailure.get()).isNull();
        assertThat(waiterFailure.get()).isInstanceOf(CredentialFailure.class).hasMessage("credential.unavailable").hasNoCause();
        assertThat(interrupted.get()).isEqualTo(interrupt);
        assertThat(fixture.versions.current(() -> BUDGET).credentialVersion()).isEqualTo(fixture.version);
    }

    private Fixture fixture() throws Exception {
        Path directory = Files.createDirectory(root.resolve("encrypted")); PrivateCredentialFiles.restrict(directory, true);
        Path master = Files.write(root.resolve("synthetic-master"), new byte[32]); PrivateCredentialFiles.restrict(master, false);
        return new Fixture(directory, master);
    }

    private static final class Fixture {
        final AtomicInteger legacyAudits = new AtomicInteger(), boundedAudits = new AtomicInteger();
        final AtomicBoolean failBounded = new AtomicBoolean();
        final AtomicReference<Long> observedBudget = new AtomicReference<>();
        final CredentialApplicationService service;
        final StoredCredentialUseSource source;
        final StoredCredentialVersionSource versions;
        final UUID version;
        Fixture(Path directory, Path master) {
            UUID owner = UUID.randomUUID();
            var environment = new MockEnvironment(); environment.setActiveProfiles("local-compose");
            environment.withProperty("archguard.agent.credentials.enabled", "true")
                .withProperty("archguard.agent.credentials.owner-id", owner.toString())
                .withProperty("archguard.agent.credentials.directory", directory.toString())
                .withProperty("archguard.agent.credentials.master-file", master.toString())
                .withProperty("archguard.agent.credentials.deployment-id", UUID.randomUUID().toString());
            var audit = new AuditRecorder() {
                @Override public void record(AuditEvent event) { legacyAudits.incrementAndGet(); }
                @Override public void record(AuditEvent event, LongSupplier remaining) {
                    boundedAudits.incrementAndGet(); observedBudget.set(remaining.getAsLong());
                    if (failBounded.get()) throw new IllegalStateException("Synthetic audit failure");
                }
            };
            service = new CredentialApplicationService(environment, () -> new CurrentActor(owner, Set.of()), audit,
                () -> "a".repeat(32), Clock.systemUTC());
            version = service.write(KEY).credentialVersion(); legacyAudits.set(0);
            source = new StoredCredentialUseSource(service); versions = new StoredCredentialVersionSource(service);
        }
    }
}
