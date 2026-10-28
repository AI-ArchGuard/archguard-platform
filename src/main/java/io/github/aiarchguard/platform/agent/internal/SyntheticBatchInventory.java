package io.github.aiarchguard.platform.agent.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 部署侧可信合成来源核验端口；以项目、清单 ID 和精确摘要解析证明，用户自由文本或风险确认不能实现这一信任边界。 */
public interface SyntheticBatchInventory {
    Optional<Proof> resolve(UUID projectId, UUID inventoryId, String manifestSha256);
    default boolean accepts(UUID projectId, UUID inventoryId, String manifestSha256) {
        return resolve(projectId, inventoryId, manifestSha256).isPresent();
    }
    record Proof(String schemaVersion, String fileSha256, String fixtureSetVersion, String fixtureArtifactSha256,
            String reviewRef, Instant reviewedAt, Instant expiresAt) { }
}
