package io.github.aiarchguard.platform.governance;

/**
 * 在已授权 Project/Repository 范围内未找到指定比较的应用异常，HTTP 层统一返回治理资源 404。
 * 范围外的比较也不能通过该 ID 被读取；归属过滤由查询实现负责。
 */
public final class ComparisonNotFoundException extends RuntimeException { }
