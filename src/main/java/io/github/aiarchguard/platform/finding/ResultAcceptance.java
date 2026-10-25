package io.github.aiarchguard.platform.finding;

import java.util.UUID;

/**
 * 供 ScanJob 编排与导入流程使用的内部报告消费边界：验证报告并保存 Finding／Evidence 的规范化数据。
 * 此入口不自行执行用户授权；调用方负责可信任务、项目及版本上下文，并另行保存原始报告和完成任务。
 */
public interface ResultAcceptance {
    /**
     * 按固定 Schema、期望项目身份和引用关系校验报告，写入成功后返回原字节摘要与版本回执。
     * scannerVersion 是调用方传入的版本标签，并非消费器从报告中查验得出的 Scanner 版本。
     */
    AcceptedResult validateAndStore(UUID projectId, UUID jobId, String expectedProjectIdentity,
                                    String scannerVersion, byte[] reportBytes);
}
