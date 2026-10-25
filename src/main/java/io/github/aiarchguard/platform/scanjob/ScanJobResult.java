package io.github.aiarchguard.platform.scanjob;

/**
 * 可下载的已接受报告及其摘要、版本信息；canonicalJson 保存接受时的原始字节，不在此重新序列化。
 * 与任务视图分开，避免列表查询携带大报告；字节数组在构造和读取时复制，防止外部修改。
 */
public record ScanJobResult(byte[] canonicalJson, String sha256, String scannerVersion, String schemaVersion) {
    public ScanJobResult {
        canonicalJson = canonicalJson.clone();
    }
    @Override public byte[] canonicalJson() { return canonicalJson.clone(); }
}
