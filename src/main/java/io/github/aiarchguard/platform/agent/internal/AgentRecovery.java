package io.github.aiarchguard.platform.agent.internal;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 启动后及定时终结过期遗留请求；只改变尚未终结的状态，不重试模型或释放未知费用，单轮最多处理有限数量。 */
@Component
public class AgentRecovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRecovery.class);
    private final AgentStore store;
    private final AgentTransitions transitions;
    private final AgentSchemaAvailability schema;
    private final Clock clock;

    private final AgentRequestRouting routing;
    private final DailyAgentRecovery dailyRecovery;

    public AgentRecovery(AgentStore store, AgentTransitions transitions, AgentSchemaAvailability schema, Clock clock) {
        this(store, transitions, schema, clock, null, null);
    }

    /** 生产装配按精确关联选择唯一恢复路径。 */
    @org.springframework.beans.factory.annotation.Autowired
    AgentRecovery(AgentStore store, AgentTransitions transitions, AgentSchemaAvailability schema, Clock clock,
            LiveRequestStore associations, DailyAgentRecovery daily) {
        this.store = store; this.transitions = transitions; this.schema = schema; this.clock = clock;
        this.routing = associations == null ? null : new AgentRequestRouting(associations);
        this.dailyRecovery = daily;
    }

    /** Schema 就绪后每轮最多处理 100 个过期请求；60 秒窗口包含结算宽限，但不会启动新模型尝试。 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    @Scheduled(scheduler = "agentRecoveryScheduler", fixedDelayString = "${archguard.agent.recovery-delay:15000}", initialDelay = 15000)
    public void recoverExpired() {
        if (!schema.ready()) return;
        // Hard provider limit is 30s; 60s includes a settlement grace period.
        Instant cutoff = Instant.now(clock).minusSeconds(60);
        try {
            for (var snapshot : store.expired(cutoff, 100)) {
                try {
                    if (routing == null) transitions.recoverExpired(snapshot, cutoff);
                    else switch (routing.recoveryMode(snapshot)) {
                        case DAILY -> dailyRecovery.recoverExpired(snapshot, cutoff);
                        case LEGACY -> transitions.recoverExpired(snapshot, cutoff);
                        case REJECTED -> LOGGER.error("Agent recovery association rejected requestId={} traceId={}",
                            snapshot.view().id(), snapshot.view().traceId());
                    }
                } catch (RuntimeException unavailable) {
                    LOGGER.error("Agent recovery unavailable requestId={} traceId={} errorType={}", snapshot.view().id(),
                        snapshot.view().traceId(), unavailable.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException unavailable) {
            LOGGER.error("Agent recovery unavailable traceId=unknown errorType={}", unavailable.getClass().getSimpleName());
        }
    }
}
