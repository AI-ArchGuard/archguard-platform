package io.github.aiarchguard.platform.repository.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.repository.InvalidRepositoryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 使用临时目录直接检验仓库挂载路径规则，不启动 Spring、扫描器或业务数据库。
 * 当前两个场景覆盖正常目录与显式非法路径，不代表已经验证符号链接或文件系统竞态。
 */
class ControlledRepositoryPathTest {
    // 每个测试使用独立的受控根目录，不访问实际部署的源码挂载。
    @TempDir Path root;

    /** 只有目录真实存在时才能登记；保存的相对路径应消除中间的冗余点段。 */
    @Test void acceptsAndNormalizesExistingRelativeDirectory() throws IOException {
        Files.createDirectories(root.resolve("team/service"));
        assertThat(ControlledRepositoryPath.normalize(root.toAbsolutePath().normalize(), "team/./service"))
            .isEqualTo("team/service");
    }

    /**
     * 分别拒绝绝对路径、越出根目录的路径、缺失目录与普通文件。
     * source.java 的内容只是合成输入；这里不验证 Java 语法，也没有创建真实仓库。
     */
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
