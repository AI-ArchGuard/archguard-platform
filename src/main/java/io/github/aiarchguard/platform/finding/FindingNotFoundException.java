package io.github.aiarchguard.platform.finding;

/**
 * 已通过项目授权后，在指定项目和任务范围内未找到 Finding 或证据时抛出。
 * Web 适配层将其映射为统一 404；项目非成员的隐藏策略由 Project 授权边界处理。
 */
public final class FindingNotFoundException extends RuntimeException { }
