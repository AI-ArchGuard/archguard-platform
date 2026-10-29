package io.github.aiarchguard.platform.governance;

/**
 * 例外在已限定比较空间内的匹配方式：FINGERPRINT 匹配一个 Platform 逻辑指纹，RULE 匹配一个规则 ID。
 * 没有隐式全局或跨项目范围，也不使用 Scanner 的运行内 Finding ID 来匹配。
 */
public enum PolicyExceptionScopeType { FINGERPRINT, RULE }
