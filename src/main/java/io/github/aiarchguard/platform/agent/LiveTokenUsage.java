package io.github.aiarchguard.platform.agent;

public record LiveTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens, int totalTokens, int reasoningTokens) { }
