package io.github.aiarchguard.platform.project.internal.domain;

import io.github.aiarchguard.platform.project.InvalidProjectException;
import java.util.regex.Pattern;

/**
 * 项目创建后保持不变的机器可读标识：3 至 63 个小写 ASCII 字母、数字或连字符，首字符为字母。
 * 本值对象只约束格式，全局唯一性由持久化约束裁决。
 */
public record ProjectKey(String value) {
    private static final Pattern VALID_KEY = Pattern.compile("^[a-z][a-z0-9-]{2,62}$");

    /** 严格接受规范格式，不自动去空白或改大小写，避免悄悄改变调用方指定的稳定标识。 */
    public ProjectKey {
        if (value == null || !VALID_KEY.matcher(value).matches()) {
            throw new InvalidProjectException(
                "Project key must be 3-63 lowercase letters, numbers, or hyphens, starting with a letter");
        }
    }
}
