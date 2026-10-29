package io.github.aiarchguard.platform.ruleset.internal;

/**
 * 应用层提交规则版本前使用的校验端口，隔离 Scanner 的进程调用细节和实现类型。
 * 当前适配器通过公共 validate-rules CLI 校验，Platform 不导入 Scanner 内部 Java 类。
 */
public interface RuleValidationPort {
    /** 校验传入的 YAML 原文；失败时抛出异常，成功时不改写或规范化该原文。 */
    void validate(String yaml);
}
