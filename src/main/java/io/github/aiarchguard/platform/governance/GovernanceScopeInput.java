package io.github.aiarchguard.platform.governance;

import java.text.Normalizer;

/**
 * 治理公开边界复用的输入规则，统一分支比较空间与幂等键格式，避免各入口产生不一致身份。
 * 这里只做格式与 NFC 规范化，不访问 Git，也不承担项目权限或仓库归属校验。
 */
public final class GovernanceScopeInput {
    private GovernanceScopeInput() { }

    /**
     * 拒绝空白、过长、带 refs/heads/ 前缀及代码列出的 Git 引用禁用形态，再返回 NFC 分支名。
     * 不静默 trim 或剥离前缀，以免把本应拒绝的输入解释成另一个比较空间。
     */
    public static String branch(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || !value.equals(value.trim())
            || value.startsWith("refs/heads/") || value.startsWith("/") || value.endsWith("/")
            || value.contains("..") || value.contains("@{") || value.contains("//")
            || value.codePoints().anyMatch(code -> Character.isISOControl(code)
                || " ~^:?*[]\\".indexOf(code) >= 0)) {
            throw new InvalidGovernanceInputException("Target branch is invalid");
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    /**
     * 保留 1–128 个 ASCII 33–126 字符组成的键，空格和控制字符也会拒绝。
     * 是否同键同输入由各用例的摘要与存储约束判断，此处不完成幂等去重。
     */
    public static String idempotencyKey(String value) {
        if (value == null || value.length() < 1 || value.length() > 128
            || value.codePoints().anyMatch(code -> code < 33 || code > 126)) {
            throw new InvalidGovernanceInputException("Idempotency-Key must be 1–128 printable ASCII characters");
        }
        return value;
    }
}
