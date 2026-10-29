package io.github.aiarchguard.platform.scanjob;

import java.util.UUID;

/**
 * 接受 CI 已生成的 Scanner 报告的公开应用边界，导入过程不调度本地文件邮箱 Runner。
 * 实现仍从当前身份上下文取得操作者并要求项目 Maintainer 权限，内部调用并不绕过授权。
 */
public interface ScanJobImportOperations {
    /** 在给定任务 ID 下校验并保存报告和 Finding，将导入任务直接完成为成功状态。 */
    void importCompleted(UUID projectId, UUID repositoryId, UUID ruleSetVersionId,
                         UUID jobId, String scannerVersion, byte[] report);
}
