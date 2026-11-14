package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 只用候选的标识定位持久化事实；有界清理不依赖发送权限、worker 或凭据。 */
@Component
public class DailyAgentRecovery {
    private final DailyAccountingService accounting;

    DailyAgentRecovery() { this.accounting = null; }

    @Autowired
    DailyAgentRecovery(DailyAccountingService accounting) { this.accounting = accounting; }

    public void recoverExpired(AgentSnapshot snapshot, Instant cutoff) {
        if (accounting == null) throw new AgentUnavailableException();
        accounting.recoverExpired(snapshot.view().projectId(), snapshot.view().id(), cutoff);
    }
}
