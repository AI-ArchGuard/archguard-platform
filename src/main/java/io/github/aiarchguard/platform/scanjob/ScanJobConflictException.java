package io.github.aiarchguard.platform.scanjob;

/** 请求与任务现状冲突，例如复用幂等键提交不同输入，或读取尚无成功报告的任务。 */
public final class ScanJobConflictException extends RuntimeException {
    public ScanJobConflictException(String message) { super(message); }
}
