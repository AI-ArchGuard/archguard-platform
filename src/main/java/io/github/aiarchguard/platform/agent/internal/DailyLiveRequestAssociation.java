package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.AgentDailyStateInvalidException;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.util.function.Consumer;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
/** 日常执行依赖精确请求关联和当前资源复核；不向外部导出发送能力。 */
final class DailyLiveRequestAssociation implements LiveRequestAssociation {
    private final DailyAgentStore store;
    private final DailyAgentControlService controls;
    private final DailyAccountingService accounting;
    private final Consumer<AgentSnapshot> resources;

    DailyLiveRequestAssociation(DailyAgentStore store, DailyAgentControlService controls, DailyAccountingService accounting) {
        throw new AgentUnavailableException();
    }

    DailyLiveRequestAssociation(DailyAgentStore store, DailyAgentControlService controls, DailyAccountingService accounting,
            Consumer<AgentSnapshot> validateResources) {
        this.store = java.util.Objects.requireNonNull(store);
        this.controls = java.util.Objects.requireNonNull(controls);
        this.accounting = java.util.Objects.requireNonNull(accounting);
        this.resources = java.util.Objects.requireNonNull(validateResources);
    }

    public Optional<LiveRequestBinding> find(UUID project, UUID request) {
        return store.findRequest(project, request).map(DailyLiveBinding::new);
    }

    private DailyAgentStore.RequestLink link(LiveRequestBinding binding) {
        if (binding instanceof DailyLiveBinding daily) return daily.link();
        throw new AgentUnavailableException();
    }

    public boolean claim(LiveRequestBinding binding, UUID actor, Instant at) {
        return store.claim(link(binding), actor, at);
    }

    public void requireCurrent(LiveRequestBinding binding, AgentSnapshot snapshot, LongSupplier remaining) {
        var link = link(binding);
        var grant = controls.requireCurrent(link.projectId(), link.grantId(), snapshot.view().purpose(), remaining);
        accounting.validateBinding(link, grant, snapshot);
        if (!link.payloadSha256().equals(DailySnapshotHashes.sha256(snapshot.input()))
                || !link.candidatesSha256().equals(DailySnapshotHashes.sha256(snapshot.candidates()))) throw new AgentDailyStateInvalidException();
        resources.accept(snapshot);
    }

    public LiveExecutionLease lease(LiveRequestBinding binding) {
        return new DailyLease(link(binding));
    }

    public void stop(LiveRequestBinding binding, String code) {
        lease(binding).stop(code);
    }

    private final class DailyLease implements LiveExecutionLease {
        private final DailyAgentStore.RequestLink link;
        DailyLease(DailyAgentStore.RequestLink link) { this.link = link; }
        public UUID projectId() { return link.projectId(); }
        public UUID requestId() { return link.requestId(); }
        public LiveAccountingOperations.Admission reserve(LongSupplier remaining) {
            return accounting.reserve(projectId(), requestId(), remaining);
        }
        public LiveAccountingOperations.SendApproval checkBeforeSend(LongSupplier remaining) {
            return accounting.checkBeforeSend(projectId(), requestId(), remaining);
        }
        public LiveAccountingOperations.Outcome settle(String price, LiveTokenUsage usage) {
            return accounting.settle(projectId(), requestId(), price, usage);
        }
        public LiveAccountingOperations.Outcome unknown() { return accounting.unknown(projectId(), requestId()); }
        public void stop(String code) { accounting.stop(projectId(), requestId(), code); }
        public void lockAndValidateSuccess(AgentSnapshot snapshot, LongSupplier remaining) {
            var current = accounting.lockAndValidateSuccess(link, snapshot, remaining);
            resources.accept(current);
            // 资源读取可等待；在写成功前再核对时效、凭据版本及当前用途角色。
            controls.requireSettled(link, current.view().purpose(), remaining);
        }
        public void completeSuccess() { accounting.completeSuccess(projectId(), requestId()); }
    }
}
