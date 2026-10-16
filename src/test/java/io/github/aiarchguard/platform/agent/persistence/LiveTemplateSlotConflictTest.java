package io.github.aiarchguard.platform.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;

class LiveTemplateSlotConflictTest {
    private static final String SLOT = "live_template_request_slots_pkey";

    @Test void recognizesOnlyTheExactPostgresUniqueConstraintUsingStructuredMetadata() {
        assertThat(LiveTemplateSlotConflict.isConflict(new DuplicateKeyException("Synthetic", error("23505", SLOT, "Untrusted provider-independent database text")))).isTrue();
    }

    @Test void neverClassifiesOtherUniqueConstraintsOrForeignKeysAsSlotConflicts() {
        assertThat(LiveTemplateSlotConflict.isConflict(error("23505", "other_constraint", SLOT))).isFalse();
        assertThat(LiveTemplateSlotConflict.isConflict(error("23503", SLOT, "Synthetic foreign key"))).isFalse();
        assertThat(LiveTemplateSlotConflict.isConflict(error("23505", null, SLOT))).isFalse();
    }

    @Test void doesNotParseMessagesOrTreatNonPostgresExceptionsAsTrustedConstraintMetadata() {
        assertThat(LiveTemplateSlotConflict.isConflict(new SQLException(SLOT, "23505"))).isFalse();
        assertThat(LiveTemplateSlotConflict.isConflict(new DuplicateKeyException(SLOT))).isFalse();
        assertThat(LiveTemplateSlotConflict.isConflict(new RuntimeException(SLOT))).isFalse();
        assertThat(LiveTemplateSlotConflict.isConflict(null)).isFalse();
    }

    @Test void causeTraversalIsBoundedEvenForACycle() {
        var first = new RuntimeException(); var second = new RuntimeException();
        first.initCause(second); second.initCause(first);
        assertThat(LiveTemplateSlotConflict.isConflict(first)).isFalse();
    }

    private PSQLException error(String state, String constraint, String untrustedMessage) {
        return new PSQLException(new ServerErrorMessage("SERROR\u0000C" + state + "\u0000M" + untrustedMessage + "\u0000"
            + (constraint == null ? "" : "n" + constraint + "\u0000")));
    }
}
