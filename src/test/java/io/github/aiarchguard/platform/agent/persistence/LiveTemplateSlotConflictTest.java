package io.github.aiarchguard.platform.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;

/**
 * 验证模板槽冲突只由 PostgreSQL 结构化约束元数据识别。其他唯一约束、外键和异常文本都不能被误判为可重放冲突。
 */
class LiveTemplateSlotConflictTest {
    private static final String SLOT = "live_template_request_slots_pkey";

    // 同时核对 SQLSTATE 与精确约束名，不能只看到唯一冲突就重放。
    @Test void recognizesOnlyTheExactPostgresUniqueConstraintUsingStructuredMetadata() {
        assertThat(LiveTemplateSlotConflict.isConflict(new DuplicateKeyException("Synthetic", error("23505", SLOT, "Untrusted provider-independent database text")))).isTrue();
    }

    @Test void neverClassifiesOtherUniqueConstraintsOrForeignKeysAsSlotConflicts() {
        assertThat(LiveTemplateSlotConflict.isConflict(error("23505", "other_constraint", SLOT))).isFalse();
        assertThat(LiveTemplateSlotConflict.isConflict(error("23503", SLOT, "Synthetic foreign key"))).isFalse();
        assertThat(LiveTemplateSlotConflict.isConflict(error("23505", null, SLOT))).isFalse();
    }

    // 异常消息是不可信文本，不能用关键词推断数据库约束。
    @Test void doesNotParseMessagesOrTreatNonPostgresExceptionsAsTrustedConstraintMetadata() {
        assertThat(LiveTemplateSlotConflict.isConflict(new SQLException(SLOT, "23505"))).isFalse();
        assertThat(LiveTemplateSlotConflict.isConflict(new DuplicateKeyException(SLOT))).isFalse();
        assertThat(LiveTemplateSlotConflict.isConflict(new RuntimeException(SLOT))).isFalse();
        assertThat(LiveTemplateSlotConflict.isConflict(null)).isFalse();
    }

    // 原因链遍历必须有界，循环异常也不能造成无限检查。
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
