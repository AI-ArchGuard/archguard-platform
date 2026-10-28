package io.github.aiarchguard.platform.agentcredential;

/** 内部元数据边界：返回已认证的当前版本状态，绝不返回解密后的 Key。 */
public interface CredentialVersionSource {
    CredentialStatus current();

    /** 沿用原执行预算；不支持有界读取的旧实现直接拒绝，不得回退无界方法。 */
    default CredentialStatus current(java.util.function.LongSupplier remainingNanos) {
        throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
    }
}
