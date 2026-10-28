package io.github.aiarchguard.platform.agent;

/** 供应商用量的内部值对象；缓存输入包含于总输入，reasoning 与总量等约束由费用策略另行校验。 */
public record LiveTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens, int totalTokens, int reasoningTokens) { }
