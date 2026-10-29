package io.github.aiarchguard.platform.agentcredential.internal;

import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import org.springframework.stereotype.Component;

/** 向其他模块公开已认证的版本元数据；项目授权仍由调用方负责，不暴露 Key。 */
@Component
final class StoredCredentialVersionSource implements CredentialVersionSource {
    private final CredentialApplicationService credentials;
    StoredCredentialVersionSource(CredentialApplicationService credentials) { this.credentials = credentials; }
    @Override public CredentialStatus current() { return credentials.internalStatus(); }
    /** 有界读取与消费共用缓存存储及槽位锁，不能另建读取实例绕过串行顺序。 */
    @Override public CredentialStatus current(java.util.function.LongSupplier remainingNanos) {
        return credentials.internalStatus(remainingNanos);
    }
}
