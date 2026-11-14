package io.github.aiarchguard.platform.agent.internal;

import java.util.UUID;
import java.util.Objects;

/** 受控真实运行装配的门面；每次使用复核策略与执行器开放状态，构造本身不启用提供方，关闭委托统一资源回收。 */
final class PersonalLiveRuntime implements AutoCloseable {
    private final PersonalLiveExecutor executor;
    private final LiveRequestDispatcher dispatcher;
    private final Runnable admission;
    private final boolean ownsExecutor;

    PersonalLiveRuntime(PersonalLiveExecutor executor, LiveRequestDispatcher dispatcher, Runnable admission) {
        this(executor, dispatcher, admission, true);
    }
    private PersonalLiveRuntime(PersonalLiveExecutor executor, LiveRequestDispatcher dispatcher, Runnable admission, boolean ownsExecutor) {
        this.ownsExecutor = ownsExecutor;
        this.executor = Objects.requireNonNull(executor); this.dispatcher = Objects.requireNonNull(dispatcher);
        this.admission = Objects.requireNonNull(admission);
    }
    /** 通过共享所有者借用队列，门面关闭不回收另一模式正在使用的资源。 */
    PersonalLiveRuntime(PersonalLiveRuntimeResources resources, LiveRequestDispatcher dispatcher, Runnable admission) {
        this(resources.executor(), dispatcher, admission, false);
    }
    void requireAvailable() { executor.requireOpen(); admission.run(); executor.requireOpen(); }
    void dispatch(UUID project, UUID request) { requireAvailable(); dispatcher.dispatch(project, request); }
    void dispatchCommitted(UUID project, UUID request) { requireAvailable(); dispatcher.dispatchCommitted(project, request); }
    @Override public void close() { if (ownsExecutor) executor.close(); }
}
