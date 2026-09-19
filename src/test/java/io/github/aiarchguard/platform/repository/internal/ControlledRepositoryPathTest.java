package io.github.aiarchguard.platform.repository.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.repository.InvalidRepositoryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlledRepositoryPathTest {
    @TempDir Path root;

    @Test void acceptsAndNormalizesExistingRelativeDirectory() throws IOException {
        Files.createDirectories(root.resolve("team/service"));
        assertThat(ControlledRepositoryPath.normalize(root.toAbsolutePath().normalize(), "team/./service"))
            .isEqualTo("team/service");
    }

    @Test void rejectsAbsoluteTraversalMissingAndSpecialFilePaths() throws IOException {
        Path file=Files.writeString(root.resolve("source.java"),"class Source {}");
        assertThatThrownBy(() -> ControlledRepositoryPath.normalize(root.toAbsolutePath().normalize(), file.toString()))
            .isInstanceOf(InvalidRepositoryException.class);
        assertThatThrownBy(() -> ControlledRepositoryPath.normalize(root.toAbsolutePath().normalize(), "../outside"))
            .isInstanceOf(InvalidRepositoryException.class);
        assertThatThrownBy(() -> ControlledRepositoryPath.normalize(root.toAbsolutePath().normalize(), "missing"))
            .isInstanceOf(InvalidRepositoryException.class);
        assertThatThrownBy(() -> ControlledRepositoryPath.normalize(root.toAbsolutePath().normalize(), "source.java"))
            .isInstanceOf(InvalidRepositoryException.class);
    }
}
