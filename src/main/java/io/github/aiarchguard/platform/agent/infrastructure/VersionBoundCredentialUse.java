package io.github.aiarchguard.platform.agent.infrastructure;

import io.github.aiarchguard.platform.agent.internal.LiveExecutionCoordinator;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.agentcredential.CredentialUseSource;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 把凭据模块的版本绑定消费桥接到真实执行核心。preflight 在密钥锁外运行，最终发送回调限同线程且只调用一次；
 * 已收到的合法用量不因清理失败被抹去。
 */
final class VersionBoundCredentialUse implements LiveExecutionCoordinator.CredentialUse {
    private static final long MAX_NANOS = Duration.ofSeconds(30).toNanos();
    private final CredentialUseSource source;
    VersionBoundCredentialUse(CredentialUseSource source) { this.source = source; }

    @Override public LiveResponsePort.Reply use(UUID expectedVersion, Runnable preflight,
            Function<String, LiveResponsePort.Reply> finalSend) {
        return useInternal(expectedVersion, null, preflight, finalSend);
    }

    @Override public LiveResponsePort.Reply use(UUID expectedVersion, LongSupplier remainingNanos, Runnable preflight,
            Function<String, LiveResponsePort.Reply> finalSend) {
        if (remainingNanos == null) throw unavailable();
        return useInternal(expectedVersion, remainingNanos, preflight, finalSend);
    }

    /**
     * 在取密钥锁前运行 preflight；用当前版本消费回调做最终发送，同线程/单次/同步作用域到期保护防止保存回调后重用。
     * 已收到回复先保留，再处理迟到或清理失败。
     */
    private LiveResponsePort.Reply useInternal(UUID expectedVersion, LongSupplier remainingNanos, Runnable preflight,
            Function<String, LiveResponsePort.Reply> finalSend) {
        if (expectedVersion == null || preflight == null || finalSend == null) throw unavailable();
        requireRemaining(remainingNanos);
        preflight.run(); // No decrypted Key or store lock; cross-module checks belong here.
        requireRemaining(remainingNanos);
        Thread callingThread = Thread.currentThread();
        var active = new AtomicBoolean(true);
        var invoked = new AtomicBoolean();
        var reply = new AtomicReference<LiveResponsePort.Reply>();
        var failure = new AtomicReference<LiveResponsePort.CallFailure>();
        RuntimeException sourceFailure = null;
        try {
            java.util.function.Consumer<String> finalOperation = apiKey -> {
                if (Thread.currentThread() != callingThread || !active.get() || failure.get() != null
                        || !invoked.compareAndSet(false, true)) {
                    failure.set(unavailable()); return;
                }
                try {
                    requireRemaining(remainingNanos);
                    // Capture first: a later timeout/cleanup failure cannot erase a billable reply.
                    reply.set(finalSend.apply(apiKey));
                }
                catch (LiveResponsePort.CallFailure known) { failure.set(new LiveResponsePort.CallFailure(known.code())); }
                catch (RuntimeException unknown) { failure.set(unavailable()); }
            };
            if (remainingNanos == null) source.consume(expectedVersion, finalOperation);
            else source.consume(expectedVersion, remainingNanos, finalOperation);
        } catch (RuntimeException unknown) { sourceFailure = unknown; }
        finally { active.set(false); }
        if (reply.get() != null) {
            boolean deadlineFailure = false;
            try { requireRemaining(remainingNanos); }
            catch (RuntimeException unavailable) { deadlineFailure = true; }
            if (sourceFailure != null || failure.get() != null || deadlineFailure) {
                var billable = reply.get();
                return new LiveResponsePort.Reply(null, "OUTPUT_INVALID", billable.usage(),
                    billable.providerResponseId(), billable.actualModelId());
            }
            return reply.get();
        }
        if (failure.get() != null) throw failure.get();
        if (sourceFailure instanceof CredentialFailure denied && denied.kind() == CredentialFailure.Kind.DENIED) {
            throw new ProjectPermissionDeniedException();
        }
        requireRemaining(remainingNanos);
        throw unavailable();
    }

    private static void requireRemaining(LongSupplier remainingNanos) {
        if (remainingNanos == null) return; // Compatibility-only legacy entry point; live core never uses it.
        if (Thread.currentThread().isInterrupted()) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
        long remaining;
        try { remaining = remainingNanos.getAsLong(); }
        catch (RuntimeException unknownClock) { throw unavailable(); }
        if (Thread.currentThread().isInterrupted() || remaining <= 0) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
        if (remaining > MAX_NANOS) throw unavailable();
    }

    private static LiveResponsePort.CallFailure unavailable() {
        return new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE");
    }
}
