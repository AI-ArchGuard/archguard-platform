package io.github.aiarchguard.platform.scanjob;

public record ScanJobResult(byte[] canonicalJson, String sha256, String scannerVersion, String schemaVersion) {
    public ScanJobResult {
        canonicalJson = canonicalJson.clone();
    }
    @Override public byte[] canonicalJson() { return canonicalJson.clone(); }
}
