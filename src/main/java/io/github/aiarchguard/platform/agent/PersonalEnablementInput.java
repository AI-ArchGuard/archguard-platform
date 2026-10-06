package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Data/operations acknowledgement only. Not a batch, fee grant, or live-call authorization. */
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
