package io.github.aiarchguard.platform.agentcredential;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 可信内部消费端口：按期望版本在同步回调内短暂使用 Key，不是明文 getter。
 * 回调必须留在当前线程，不得保留或记录 Key、重入凭据操作，或进入数据库/其他模块锁。
 * 替换、删除和版本核验共用同一进程槽位锁；这不代表多进程或外部授权与网络发送原子提交。
 * Trusted internal boundary, not a credential getter or an HTTP API.
 * The callback runs synchronously on the calling thread and must not retain or log the Key,
 * enter database-backed or locking module APIs, schedule later Key use, or reenter credential operations.
 * The final network callback may recheck an already-captured in-process identity capability;
 * that check must not access a database or acquire another module's lock.
 * A current version is checked under the same process-local lock used by replacement and deletion.
 */
public interface CredentialUseSource {
    /** 兼容消费入口，可中断等待但没有执行期限；真实有界路径使用下面的重载。 */
    void consume(UUID expectedVersion, Consumer<String> finalOperation);

    /**
     * 沿用原单调时钟剩余预算，最多 30 秒；不支持的实现直接失败，禁止退回无界消费。
     * 期限供应器只能读时钟和中断状态，不能在持锁时访问身份、审计或其他模块。
     * Remaining time from the original monotonic deadline, at most 30 seconds.
     * The trusted supplier must only read that clock/interruption state: no identity,
     * audit, database or other module calls while the credential slot may be locked.
     * Legacy implementations fail closed rather than falling back to unbounded use.
     */
    default void consume(UUID expectedVersion, LongSupplier remainingNanos, Consumer<String> finalOperation) {
        throw new CredentialFailure(CredentialFailure.Kind.UNAVAILABLE);
    }
}
