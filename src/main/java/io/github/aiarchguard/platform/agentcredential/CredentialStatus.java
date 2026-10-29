package io.github.aiarchguard.platform.agentcredential;

import java.time.Instant;
import java.util.UUID;

/** 只写凭据管理只返回是否配置、版本和时间，绝不返回秘密或其指纹。 */
public record CredentialStatus(boolean configured, UUID credentialVersion, Instant updatedAt) {
    public static CredentialStatus absent() { return new CredentialStatus(false, null, null); }
}
