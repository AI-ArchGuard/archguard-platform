package io.github.aiarchguard.platform.agent.persistence;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.internal.LiveMonetaryBudgetStore;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 共享日金额 SQL；连接和事务由调用方提供，不携带批次或授权次数。 */
final class JdbcLiveMonetaryBudgetStore implements LiveMonetaryBudgetStore {
    private final JdbcClient jdbc;
    JdbcLiveMonetaryBudgetStore(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public MoneyUsage lock(String scope, UUID id, LocalDate day) {
        jdbc.sql("INSERT INTO agent.live_budget_usage(scope,scope_id,utc_day) VALUES (:s,:id,:day) ON CONFLICT DO NOTHING")
            .param("s", scope).param("id", id).param("day", day).update();
        return jdbc.sql("SELECT reserved_microusd,spent_microusd FROM agent.live_budget_usage WHERE scope=:s AND scope_id=:id AND utc_day=:day FOR UPDATE")
            .param("s", scope).param("id", id).param("day", day).query((rs, row) -> new MoneyUsage(rs.getLong(1), rs.getLong(2))).single();
    }
    @Override public void reserve(String scope, UUID id, LocalDate day, long reserved) {
        changed(jdbc.sql("UPDATE agent.live_budget_usage SET reserved_microusd=reserved_microusd+:n WHERE scope=:s AND scope_id=:id AND utc_day=:day")
            .param("n", reserved).param("s", scope).param("id", id).param("day", day).update());
    }
    @Override public void settle(String scope, UUID id, LocalDate day, long reserved, long actual) {
        changed(jdbc.sql("""
            UPDATE agent.live_budget_usage SET reserved_microusd=reserved_microusd-:reserved,spent_microusd=spent_microusd+:cost
            WHERE scope=:s AND scope_id=:id AND utc_day=:day AND reserved_microusd>=:reserved
            """).param("reserved", reserved).param("cost", actual).param("s", scope).param("id", id).param("day", day).update());
    }
    private void changed(int count) { if (count != 1) throw new AgentUnavailableException(); }
}
