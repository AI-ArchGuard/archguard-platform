package io.github.aiarchguard.platform.agent.internal;

import java.util.UUID;
import java.util.Objects;

/** Controlled assembly facade; construction alone does not enable a provider. */
final class PersonalLiveRuntime implements AutoCloseable {
    private final PersonalLiveExecutor executor;
    private final LiveRequestDispatcher dispatcher;
    private final Runnable admission;
    PersonalLiveRuntime(PersonalLiveExecutor executor, LiveRequestDispatcher dispatcher, Runnable admission) {
        this.executor = Objects.requireNonNull(executor); this.dispatcher = Objects.requireNonNull(dispatcher);
        this.admission = Objects.requireNonNull(admission);
    }
    void requireAvailable() { executor.requireOpen(); admission.run(); executor.requireOpen(); }
    void dispatch(UUID project, UUID request) { requireAvailable(); dispatcher.dispatch(project, request); }
    @Override public void close() { executor.close(); }
}
