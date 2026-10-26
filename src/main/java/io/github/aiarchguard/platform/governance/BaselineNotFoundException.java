package io.github.aiarchguard.platform.governance;

/**
 * 治理应用边界的“未找到兼容基线”信号，可能是比较空间、活动选择或指定版本不存在。
 * HTTP 查询将其映射为 404；门禁求值会将缺少基线保存为配置类 ERROR，而不是按空集合通过。
 */
public final class BaselineNotFoundException extends RuntimeException { }
