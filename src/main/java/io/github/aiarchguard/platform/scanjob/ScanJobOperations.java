package io.github.aiarchguard.platform.scanjob;

import java.util.List;
import java.util.UUID;

/**
 * 扫描任务面向 HTTP 等调用者的应用接口，统一承担项目授权和任务生命周期操作。
 * 提交、取消要求 Maintainer；读取、列表和结果下载要求 Viewer。
 */
public interface ScanJobOperations {
    /** 按项目范围的幂等键入队；同键同仓库/规则版本返回原任务，不同输入产生冲突。 */
    ScanJobView submit(UUID projectId, UUID repositoryId, UUID ruleSetVersionId, String idempotencyKey);
    /** 读取属于该项目的单个任务，不返回内部租约、attempt token 或报告字节。 */
    ScanJobView get(UUID projectId, UUID jobId);
    /** 以从零开始的页码读取任务，页大小限制为 1 到 100。 */
    List<ScanJobView> list(UUID projectId, int page, int size);
    /** 排队任务可直接取消；运行任务先记录取消请求，返回值不证明 Runner 进程已经退出。 */
    ScanJobView cancel(UUID projectId, UUID jobId);
    /** 仅返回状态成功且已有报告的任务结果，失败附件不通过此入口下载。 */
    ScanJobResult result(UUID projectId, UUID jobId);
}
