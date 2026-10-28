package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 个人数据及操作风险确认输入，绑定凭据版本、模型映射和价格来源；不授予批次费用或单次调用权限，toString 省略详情。
 */
public record PersonalEnablementInput(String schemaVersion, String scope, String accountRef, String deploymentRef,
        Instant expiresAt, List<Source> sources, List<String> unknowns, Boolean riskAccepted,
        String modelAlias, String mappingSnapshot, List<String> allowedResponseModels,
        String priceCatalogVersion, Instant priceCatalogExpiresAt, String secretCheckRef, String networkCheckRef,
        String dataScope, List<String> revocationConditions, UUID credentialVersion) {
    public PersonalEnablementInput {
        sources = sources == null ? null : List.copyOf(sources);
        unknowns = unknowns == null ? null : List.copyOf(unknowns);
        allowedResponseModels = allowedResponseModels == null ? null : List.copyOf(allowedResponseModels);
        revocationConditions = revocationConditions == null ? null : List.copyOf(revocationConditions);
    }
    public record Source(String kind, String url, String version, Instant checkedAt) { }
    @Override public String toString() { return "PersonalEnablementInput[details omitted]"; }
}
