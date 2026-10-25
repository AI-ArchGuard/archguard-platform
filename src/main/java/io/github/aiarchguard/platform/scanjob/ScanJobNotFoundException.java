package io.github.aiarchguard.platform.scanjob;

/** 授权后在指定项目下找不到扫描任务；不允许用其他项目的任务 ID 跨范围读取。 */
public final class ScanJobNotFoundException extends RuntimeException {
}
