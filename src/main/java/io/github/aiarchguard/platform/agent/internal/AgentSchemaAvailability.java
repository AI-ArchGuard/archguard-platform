package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Agent schema failure must not prevent deterministic Platform routes from starting. */
@Component
public class AgentSchemaAvailability {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSchemaAvailability.class);
    private final Runnable migrate;
    private volatile boolean ready;

    @Autowired
    public AgentSchemaAvailability(DataSource dataSource) {
        this(() -> Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/agent-migration")
            .defaultSchema("public")
            .table("flyway_agent_schema_history")
            .baselineOnMigrate(true)
            .baselineVersion("8")
            .load().migrate());
    }

    AgentSchemaAvailability(Runnable migrate) { this.migrate = migrate; }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void migrateOnReady() {
        try {
            migrate.run();
            ready = true;
            LOGGER.info("Agent schema is ready");
        } catch (RuntimeException failed) {
            ready = false;
            LOGGER.error("Agent schema unavailable errorType={}", failed.getClass().getSimpleName());
        }
    }

    public boolean ready() { return ready; }

    public void requireReady() {
        if (!ready) throw new AgentUnavailableException();
    }
}
