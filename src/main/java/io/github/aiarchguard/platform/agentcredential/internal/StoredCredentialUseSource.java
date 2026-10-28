package io.github.aiarchguard.platform.agentcredential.internal;

import io.github.aiarchguard.platform.agentcredential.CredentialUseSource;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** 将可信内部消费委托给管理服务缓存的同一存储，避免替换、删除和消费产生不同锁域。 */
@Component
final class StoredCredentialUseSource implements CredentialUseSource {
    private final CredentialApplicationService credentials;
    StoredCredentialUseSource(CredentialApplicationService credentials) { this.credentials = credentials; }
    /** 兼容同步消费；owner、意图审计和版本核验由服务及存储完成。 */
    @Override public void consume(UUID expectedVersion, Consumer<String> finalOperation) {
        credentials.internalConsume(expectedVersion, finalOperation);
    }
    /** 不重置时钟，将原剩余预算继续传入服务和槽位锁等待。 */
    @Override public void consume(UUID expectedVersion, LongSupplier remainingNanos, Consumer<String> finalOperation) {
        credentials.internalConsume(expectedVersion, remainingNanos, finalOperation);
    }
}
