package io.github.aiarchguard.platform.agent.persistence;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 每次实际核对表与成功迁移事实；不缓存、不把不一致或探测错误视为旧库。 */
final class DailySchemaCapability {
    private DailySchemaCapability() {}
    static boolean current(JdbcClient jdbc) {
        try {
            boolean table=jdbc.sql("SELECT to_regclass('agent.daily_request_links') IS NOT NULL").query(Boolean.class).single();
            boolean migrated=jdbc.sql("SELECT EXISTS(SELECT 1 FROM public.flyway_agent_schema_history WHERE version='18' AND type='SQL' AND success)").query(Boolean.class).single();
            if(table!=migrated) throw new AgentUnavailableException();
            return table;
        } catch(RuntimeException unavailable) { throw new AgentUnavailableException(); }
    }
}
