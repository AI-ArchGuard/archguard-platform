package io.github.aiarchguard.platform.project.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.project.InvalidProjectException;
import org.junit.jupiter.api.Test;

class ProjectValueObjectsTest {
    @Test
    void acceptsAValidProjectKey() {
        assertThat(new ProjectKey("archguard-core").value()).isEqualTo("archguard-core");
    }

    @Test
    void rejectsUppercaseAndShortKeys() {
        assertThatThrownBy(() -> new ProjectKey("AG"))
            .isInstanceOf(InvalidProjectException.class);
    }

    @Test
    void trimsAndNormalizesProjectNames() {
        assertThat(new ProjectName("  Cafe\u0301  ").value()).isEqualTo("Café");
    }

    @Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> new ProjectName("invalid\nname"))
            .isInstanceOf(InvalidProjectException.class);
    }
}
