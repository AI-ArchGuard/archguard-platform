package io.github.aiarchguard.platform.governance;

import java.util.List;

/**
 * 治理只读列表的分页响应，供门禁历史和 PR 列表共用；不查询总条数。
 * 应用读取层要求 page 从 0 开始、size 在 1–50 之间，本 DTO 本身不执行参数范围校验。
 *
 * @param items 本页至多 size 条的快照列表
 * @param page 请求的零起始页号
 * @param size 请求的页大小，不保证本页一定有这么多条
 * @param hasMore 本次查询是否多取到一条记录，只表示当次读取时后面仍有记录
 */
public record GovernancePage<T>(List<T> items, int page, int size, boolean hasMore) {
    public GovernancePage { items = List.copyOf(items); }

    /**
     * 接收上游至多 size+1 条记录，用额外一条推导 hasMore，再截去探测记录。
     * 调用者需先校验分页参数并按稳定顺序查询；这里只负责封装，不自行访问或计数数据库。
     */
    public static <T> GovernancePage<T> of(List<T> fetched, int page, int size) {
        boolean more = fetched.size() > size;
        return new GovernancePage<>(more ? fetched.subList(0, size) : fetched, page, size, more);
    }
}
