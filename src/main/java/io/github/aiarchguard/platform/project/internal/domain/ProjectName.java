package io.github.aiarchguard.platform.project.internal.domain;

import io.github.aiarchguard.platform.project.InvalidProjectException;
import java.text.Normalizer;

/**
 * 面向展示的项目名称值对象，规范化后须为 1 至 120 个 Unicode 码点，且不含 ISO 控制字符。
 * 创建与修改名称共用同一规则，持久化重建也经过该构造过程。
 */
public record ProjectName(String value) {
    /** 将原始输入转为可保存的名称；规则检查使用规范化后的值。 */
    public ProjectName {
        if (value == null) {
            throw new InvalidProjectException("Project name is required");
        }
        // 先应用 String.trim()，再做 NFC 规范化，让规范等价的 Unicode 写法得到一致表示。
        value = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        // 按 Unicode 码点计数，避免补充平面字符因占用两个 UTF-16 单元而被重复计算。
        int length = value.codePointCount(0, value.length());
        // 规范化后再检查，既拒绝被修剪为空的名称，也阻止控制字符进入展示文本。
        if (length == 0 || length > 120 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidProjectException("Project name must contain 1-120 characters without control characters");
        }
    }
}
