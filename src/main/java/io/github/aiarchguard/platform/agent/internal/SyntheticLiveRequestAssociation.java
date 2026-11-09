package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
/** 旧批次只提供其原有的领取及记账能力。 */
final class SyntheticLiveRequestAssociation implements LiveRequestAssociation {
    private final LiveRequestStore store;
    private final LiveAccountingOperations accounting;

    SyntheticLiveRequestAssociation(LiveRequestStore store, LiveAccountingOperations accounting) {
        this.store = store;
        this.accounting = accounting;
    }

    public Optional<LiveRequestBinding> find(UUID project, UUID request) {
        return store.find(project, request).map(SyntheticLiveBinding::new);
    }

    private LiveRequestStore.Link link(LiveRequestBinding binding) {
        if (binding instanceof SyntheticLiveBinding synthetic) return synthetic.link();
        throw new AgentUnavailableException();
    }

    public boolean claim(LiveRequestBinding binding, UUID actor, Instant at) {
        return store.claim(link(binding), actor, at);
    }

    public void requireCurrent(LiveRequestBinding binding, AgentSnapshot snapshot, LongSupplier remaining) {
        // 合成模式在精确领取 SQL 及原账本预检中复核版本和准入。
        link(binding);
    }

    public LiveExecutionLease lease(LiveRequestBinding binding) {
        var link = link(binding);
        return new BatchLease(accounting, link.projectId(), link.batchId(), link.templateId(), link.requestId());
    }

    public void stop(LiveRequestBinding binding, String code) {
        lease(binding).stop(code);
    }

    record BatchLease(LiveAccountingOperations accounting, UUID projectId, UUID batchId, UUID templateId,
            UUID requestId) implements LiveExecutionLease {
        public LiveAccountingOperations.Admission reserve(LongSupplier remaining) {
            return accounting.reserve(projectId, batchId, templateId, requestId, remaining);
        }
        public LiveAccountingOperations.SendApproval checkBeforeSend(LongSupplier remaining) {
            return accounting.checkBeforeSend(projectId, batchId, templateId, requestId, remaining);
        }
        public LiveAccountingOperations.Outcome settle(String price, io.github.aiarchguard.platform.agent.LiveTokenUsage usage) {
            return accounting.settle(projectId, requestId, price, usage);
        }
        public LiveAccountingOperations.Outcome unknown() { return accounting.unknown(projectId, requestId); }
        public void stop(String code) { accounting.stopBatch(projectId, batchId, requestId, code); }
        // 旧批次没有日常授权的活动槽，结果事务保留原语义。
        public void lockAndValidateSuccess(AgentSnapshot snapshot, LongSupplier remaining) { }
        public void completeSuccess() { }
    }
}
