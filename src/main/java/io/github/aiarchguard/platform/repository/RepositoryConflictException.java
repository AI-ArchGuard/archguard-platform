package io.github.aiarchguard.platform.repository;

/**
 * 登记写入触发唯一键冲突时由应用层抛出，Web 层统一映射为仓库 key 冲突和 HTTP 409。
 * 当前实现未区分具体数据库约束，因此此类型不能用来判断究竟是哪一个唯一键发生冲突。
 */
public final class RepositoryConflictException extends RuntimeException {
}
