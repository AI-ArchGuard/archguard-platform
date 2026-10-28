package io.github.aiarchguard.platform.agentdocument;

/** 上传原始字节超过文档上限，由 HTTP 层映射为 413。 */
public final class DocumentTooLargeException extends RuntimeException {}
