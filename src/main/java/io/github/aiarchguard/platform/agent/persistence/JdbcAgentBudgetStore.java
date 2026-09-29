package io.github.aiarchguard.platform.agent.persistence;

import io.github.aiarchguard.platform.agent.internal.AgentBudgetStore;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentBudgetStore implements AgentBudgetStore {
    private static final UUID DEPLOYMENT = new UUID(0, 0);
    private static final long PROJECT_LIMIT = 5_000_000;
    private static final long DEPLOYMENT_LIMIT = 20_000_000;
    private final JdbcClient jdbc;

    public JdbcAgentBudgetStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean reserve(UUID projectId, LocalDate day, long estimate) {
        if (estimate < 0 || estimate > 100_000) return false;
        create("DEPLOYMENT", DEPLOYMENT, day);
        create("PROJECT", projectId, day);
        Budget deployment = lock("DEPLOYMENT", DEPLOYMENT, day);
        Budget project = lock("PROJECT", projectId, day);
        if (deployment.spent() + deployment.reserved() + estimate > DEPLOYMENT_LIMIT
                || project.spent() + project.reserved() + estimate > PROJECT_LIMIT) return false;
        increase("DEPLOYMENT", DEPLOYMENT, day, estimate);
        increase("PROJECT", projectId, day, estimate);
        return true;
    }

    @Override
    public void settle(UUID projectId, LocalDate day, long reserved, long actual) {
        if (reserved < 0 || actual < 0 || actual > reserved) {
            throw new IllegalArgumentException("Agent cost exceeds its reservation");
        }
        settleOne("DEPLOYMENT", DEPLOYMENT, day, reserved, actual);
        settleOne("PROJECT", projectId, day, reserved, actual);
    }

    private void create(String scope, UUID id, LocalDate day) {
        jdbc.sql("""
            INSERT INTO agent.budget_usage (scope, scope_id, utc_day) VALUES (:scope, :id, :day)
            ON CONFLICT DO NOTHING
            """).param("scope", scope).param("id", id).param("day", day).update();
    }

    private Budget lock(String scope, UUID id, LocalDate day) {
        return jdbc.sql("""
            SELECT reserved_microusd, spent_microusd FROM agent.budget_usage
            WHERE scope=:scope AND scope_id=:id AND utc_day=:day FOR UPDATE
            """).param("scope", scope).param("id", id).param("day", day)
            .query((rs, row) -> new Budget(rs.getLong(1), rs.getLong(2))).single();
    }

    private void increase(String scope, UUID id, LocalDate day, long estimate) {
        jdbc.sql("""
            UPDATE agent.budget_usage SET reserved_microusd=reserved_microusd+:estimate
            WHERE scope=:scope AND scope_id=:id AND utc_day=:day
            """).param("estimate", estimate).param("scope", scope).param("id", id).param("day", day).update();
    }

    private void settleOne(String scope, UUID id, LocalDate day, long reserved, long actual) {
        int changed = jdbc.sql("""
            UPDATE agent.budget_usage
            SET reserved_microusd=reserved_microusd-:reserved, spent_microusd=spent_microusd+:actual
            WHERE scope=:scope AND scope_id=:id AND utc_day=:day AND reserved_microusd>=:reserved
            """).param("reserved", reserved).param("actual", actual).param("scope", scope).param("id", id)
            .param("day", day).update();
        if (changed != 1) throw new IllegalStateException("Agent reservation was missing");
    }

    private record Budget(long reserved, long spent) {}
}
