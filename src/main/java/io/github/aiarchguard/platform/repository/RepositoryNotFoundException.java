package io.github.aiarchguard.platform.repository;

/**
 * 指定项目下没有匹配的仓库登记，包含仓库 UUID 实际属于其他项目的情况，对外映射为 HTTP 404。
 * 项目本身的可见性由 Project 授权边界处理，此异常只表达仓库归属查询未命中。
 */
public final class RepositoryNotFoundException extends RuntimeException {
}
