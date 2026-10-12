package io.github.aiarchguard.platform.agent.infrastructure;

import io.github.aiarchguard.platform.agent.internal.LiveExecutionCoordinator;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.agentcredential.CredentialUseSource;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Not a bean: adding a trusted consumer does not assemble or authorize a real model client. */
final class VersionBoundCredentialUse implements LiveExecutionCoordinator.CredentialUse {
    private final CredentialUseSource source;
    VersionBoundCredentialUse(CredentialUseSource source) { this.source = source; }

    @Override public LiveResponsePort.Reply use(UUID expectedVersion, Runnable preflight,
            Function<String, LiveResponsePort.Reply> finalSend) {
        if (expectedVersion == null || preflight == null || finalSend == null) throw unavailable();
        preflight.run(); // No decrypted Key or store lock; cross-module checks belong here.
        Thread callingThread = Thread.currentThread();
        var active = new AtomicBoolean(true);
        var invoked = new AtomicBoolean();
        var reply = new AtomicReference<LiveResponsePort.Reply>();
        var failure = new AtomicReference<LiveResponsePort.CallFailure>();
        RuntimeException sourceFailure = null;
        try {
            source.consume(expectedVersion, apiKey -> {
                if (Thread.currentThread() != callingThread || !active.get() || failure.get() != null
                        || !invoked.compareAndSet(false, true)) {
                    failure.set(unavailable()); return;
                }
                try { reply.set(finalSend.apply(apiKey)); }
                catch (LiveResponsePort.CallFailure known) { failure.set(new LiveResponsePort.CallFailure(known.code())); }
                catch (RuntimeException unknown) { failure.set(unavailable()); }
            });
        } catch (RuntimeException unknown) { sourceFailure = unknown; }
        finally { active.set(false); }
        if (reply.get() != null) {
            if (sourceFailure != null || failure.get() != null) {
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
        throw unavailable();
    }

    private static LiveResponsePort.CallFailure unavailable() {
        return new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE");
    }
}
