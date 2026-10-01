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

/** Expire orphaned work; never retry a provider attempt or release unknown charges. */
@Component
public class AgentRecovery {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRecovery.class);
    private final AgentStore store;
    private final AgentTransitions transitions;
    private final AgentSchemaAvailability schema;
    private final Clock clock;

    public AgentRecovery(AgentStore store, AgentTransitions transitions, AgentSchemaAvailability schema, Clock clock) {
        this.store = store; this.transitions = transitions; this.schema = schema; this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    @Scheduled(scheduler = "agentRecoveryScheduler", fixedDelayString = "${archguard.agent.recovery-delay:15000}", initialDelay = 15000)
    public void recoverExpired() {
        if (!schema.ready()) return;
        // Hard provider limit is 30s; 60s includes a settlement grace period.
        Instant cutoff = Instant.now(clock).minusSeconds(60);
        try {
            for (var snapshot : store.expired(cutoff, 100)) transitions.recoverExpired(snapshot, cutoff);
        } catch (RuntimeException unavailable) {
            LOGGER.error("Agent recovery unavailable traceId=unknown errorType={}", unavailable.getClass().getSimpleName());
        }
    }
}
