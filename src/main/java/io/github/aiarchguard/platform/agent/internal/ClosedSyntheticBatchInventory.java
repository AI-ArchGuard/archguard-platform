package io.github.aiarchguard.platform.agent.internal;

import java.util.Optional;
import java.util.UUID;

/** 默认拒绝的合成来源端口；缺少受控清单配置时返回空证明，用户提交的摘要或复选框不能代替来源核验。 */
final class ClosedSyntheticBatchInventory implements SyntheticBatchInventory {
    @Override public Optional<Proof> resolve(UUID project, UUID inventory, String digest) { return Optional.empty(); }
}
