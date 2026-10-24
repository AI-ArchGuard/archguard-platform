package io.github.aiarchguard.platform.project.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.project.InvalidProjectException;
import org.junit.jupiter.api.Test;

/** 不启动 Spring 或数据库，直接验证项目 key/name 值对象的校验与名称规范化规则。 */
class ProjectValueObjectsTest {
    /** 合法 key 原样保留，确保稳定标识不会被隐式重写。 */
    @Test
    void acceptsAValidProjectKey() {
        assertThat(new ProjectKey("archguard-core").value()).isEqualTo("archguard-core");
    }

    /** 此处使用同时包含大写且过短的输入，验证它会被拒绝，不分别穷举两类边界。 */
    @Test
    void rejectsUppercaseAndShortKeys() {
        assertThatThrownBy(() -> new ProjectKey("AG"))
            .isInstanceOf(InvalidProjectException.class);
    }

    /** 去除外围空格后转为 NFC，使组合重音字符和等价的预组字符具有相同名称。 */
    @Test
    void trimsAndNormalizesProjectNames() {
        assertThat(new ProjectName("  Cafe\u0301  ").value()).isEqualTo("Café");
    }

    /** 控制字符不能成为展示名称的一部分，测试字符串内的换行是被拒绝的输入。 */
    @Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> new ProjectName("invalid\nname"))
            .isInstanceOf(InvalidProjectException.class);
    }
}
