package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Instant;

/** 日常过期恢复仅接受原快照与原截止点；尚未装配本地清理。 */
@org.springframework.stereotype.Component
public class DailyAgentRecovery {
    public void recoverExpired(AgentSnapshot snapshot, Instant cutoff) { throw new AgentUnavailableException(); }
}
