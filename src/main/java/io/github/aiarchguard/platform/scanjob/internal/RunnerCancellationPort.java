package io.github.aiarchguard.platform.scanjob.internal;

import java.util.UUID;

/**
 * 应用服务向 Runner 适配器发出取消意图的内部端口，token 将信号限定到一次执行尝试。
 * 没有退出确认返回值，调用成功也不等于外部进程已经停止。
 */
interface RunnerCancellationPort {
    /** 尽力通知给定 attempt；任务取消状态由存储更新和后续结果收集分别推进。 */
    void request(UUID jobId, UUID attemptToken);
}
