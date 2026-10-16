package io.github.aiarchguard.platform.agent.persistence;

import java.sql.SQLException;

/** Narrow persistence translation; no server message or database identifier crosses the API. */
final class LiveTemplateSlotConflict {
    private LiveTemplateSlotConflict() { }

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
