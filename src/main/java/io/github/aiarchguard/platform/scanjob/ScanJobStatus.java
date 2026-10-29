package io.github.aiarchguard.platform.scanjob;

/**
 * 任务执行状态：QUEUED 等待认领，RUNNING 表示已分配 attempt，CANCEL_REQUESTED 仅表示已请求取消。
 * SUCCEEDED、FAILED、CANCELLED 是终态；SUCCEEDED 还需结合 outcome 区分通过与发现违规。
 * RUNNING 也可能尚在投递文件，取消请求本身不证明进程已停止。
 */
public enum ScanJobStatus {
    QUEUED, RUNNING, CANCEL_REQUESTED, SUCCEEDED, FAILED, CANCELLED
}
