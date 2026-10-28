package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.PersonalEnablementInput;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 验证个人风险确认的固定来源、未知事项、撤销条件、期限及版本组合。历史记录保持可读，但只有显式当前模型映射可授权新上下文策略准入。
 */
final class PersonalEnablementPolicy {
    static final String PRICE_VERSION = io.github.aiarchguard.platform.agent.LiveCostPolicy.VERSION;
    static final String CONTEXT_PRICE_VERSION = io.github.aiarchguard.platform.agent.LiveCostPolicy.CONTEXT_VERSION;
    private static final Set<String> UNKNOWNS = Set.of("PROCESSING_REGION", "STORAGE_REGION", "TRAINING", "HUMAN_REVIEW",
        "LOG_RETENTION", "CACHE_ISOLATION", "CACHE_RETENTION", "SUBPROCESSORS");
    private static final Set<String> REVOCATIONS = Set.of("OWNER_REVOKED", "SCOPE_CHANGED", "POLICY_CHANGED", "MODEL_CHANGED",
        "PRICE_CHANGED", "SECRET_CHECK_INVALID", "NETWORK_CHECK_INVALID");
    private static final Map<String, String> SOURCES = Map.of(
        "TERMS", "https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html",
        "PRIVACY", "https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html",
        "CACHE", "https://api-docs.deepseek.com/guides/kv_cache/",
        "MODEL_PRICE", "https://api-docs.deepseek.com/quick_start/pricing/",
        "PROTOCOL", "https://api-docs.deepseek.com/guides/responses_api/");
    private PersonalEnablementPolicy() { }

    /**
     * 按显式 Schema/映射/价格组合核对确认，来源 URL 只是固定材料引用，代码不会联网核对材料或替用户接受未知风险。
     */
    static void validate(PersonalEnablementInput value, Instant now) {
        if (value == null || !profile(value) || !"PERSONAL".equals(value.scope())
                || !Boolean.TRUE.equals(value.riskAccepted()) || !"SYNTHETIC_ACCEPTANCE".equals(value.dataScope())
                || !"deepseek-flash".equals(value.modelAlias())
                || value.credentialVersion() == null || value.allowedResponseModels() == null
                || !value.allowedResponseModels().equals(java.util.List.of("deepseek-flash"))
                || !ref(value.accountRef()) || !ref(value.deploymentRef()) || !ref(value.secretCheckRef()) || !ref(value.networkCheckRef())
                || !expiry(value.expiresAt(), now) || !expiry(value.priceCatalogExpiresAt(), now)
                || value.expiresAt().isAfter(value.priceCatalogExpiresAt())
                || value.unknowns() == null || value.unknowns().size() != UNKNOWNS.size()
                || !new HashSet<>(value.unknowns()).equals(UNKNOWNS)
                || value.revocationConditions() == null || value.revocationConditions().size() != REVOCATIONS.size()
                || !new HashSet<>(value.revocationConditions()).equals(REVOCATIONS)
                || value.sources() == null || value.sources().size() < 4 || value.sources().size() > 5) invalid();
        Set<String> kinds = new HashSet<>();
        for (var source : value.sources()) {
            if (source == null || source.kind() == null || !SOURCES.containsKey(source.kind())
                    || !SOURCES.get(source.kind()).equals(source.url()) || !kinds.add(source.kind()) || !ref(source.version())
                    || source.checkedAt() == null || source.checkedAt().isAfter(now)
                    || source.checkedAt().isBefore(now.minus(Duration.ofDays(7)))) invalid();
        }
        if (!kinds.containsAll(Set.of("TERMS", "PRIVACY", "CACHE", "MODEL_PRICE"))) invalid();
    }
    private static boolean profile(PersonalEnablementInput value) {
        // A schema version is explicit risk acceptance; matching the model alias alone cannot upgrade it.
        return ("0.2.0".equals(value.schemaVersion()) && PRICE_VERSION.equals(value.priceCatalogVersion())
                && "deepseek-v4.1-flash-2026-10-06".equals(value.mappingSnapshot()))
            || ("0.3.0".equals(value.schemaVersion()) && CONTEXT_PRICE_VERSION.equals(value.priceCatalogVersion())
                && "deepseek-v4.1-flash-2026-10-15".equals(value.mappingSnapshot()))
            || currentMapping(value);
    }
    /** Explicit current acknowledgement only; historical prices and aliases cannot upgrade a mapping. */
    static boolean currentMapping(PersonalEnablementInput value) {
        return value != null && "0.4.0".equals(value.schemaVersion())
            && "DeepSeek-V4.1-Flash-1017".equals(value.mappingSnapshot())
            && CONTEXT_PRICE_VERSION.equals(value.priceCatalogVersion())
            && "deepseek-flash".equals(value.modelAlias())
            && java.util.List.of("deepseek-flash").equals(value.allowedResponseModels());
    }
    private static boolean ref(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") && !value.startsWith("sk-");
    }
    private static boolean expiry(Instant value, Instant now) {
        return value != null && value.isAfter(now) && !value.isAfter(now.plus(Duration.ofDays(7)));
    }
    private static void invalid() { throw new AgentInvalidException(); }
}
