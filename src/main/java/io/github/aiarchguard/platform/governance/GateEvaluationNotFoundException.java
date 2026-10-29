package io.github.aiarchguard.platform.governance;

/**
 * 在调用者可读的 Project/Repository 内未找到门禁记录，交给 HTTP 层映射为 404。
 * 这是资源读取失败，与已成功保存但 outcome 为 ERROR 的门禁结果不同。
 */
public final class GateEvaluationNotFoundException extends RuntimeException { }
