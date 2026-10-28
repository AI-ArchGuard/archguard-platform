package io.github.aiarchguard.platform.agent.persistence;

import java.sql.SQLException;

/**
 * 仅把特定模板槽位唯一约束冲突识别为稳定 409；读取固定 PostgreSQL 结构化元数据，不把数据库消息或标识传过 API。
 */
final class LiveTemplateSlotConflict {
    private LiveTemplateSlotConflict() { }

    /**
     * 有界遍历 cause，只读取固定 PostgreSQL 唯一冲突 SQLState 和指定约束名；
     * 反射用于保持驱动 runtime 作用域，不解析错误文本。
     */
    static boolean isConflict(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (!"org.postgresql.util.PSQLException".equals(failure.getClass().getName())
                    || !(failure instanceof SQLException sql) || !"23505".equals(sql.getSQLState())) continue;
            try {
                // The JDBC driver remains runtime-scoped. Read only fixed structured metadata,
                // never a message/detail/body and never caller-selected classes or methods.
                Object server = failure.getClass().getMethod("getServerErrorMessage").invoke(failure);
                if (server != null && "org.postgresql.util.ServerErrorMessage".equals(server.getClass().getName())
                        && "live_template_request_slots_pkey".equals(server.getClass().getMethod("getConstraint").invoke(server))) return true;
            } catch (ReflectiveOperationException | SecurityException unavailableMetadata) {
                return false;
            }
        }
        return false;
    }
}
