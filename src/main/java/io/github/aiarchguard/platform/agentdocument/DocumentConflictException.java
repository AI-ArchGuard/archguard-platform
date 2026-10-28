package io.github.aiarchguard.platform.agentdocument;

/** 同一项目的幂等键已绑定不同上传输入，由 HTTP 层映射为冲突。 */
public final class DocumentConflictException extends RuntimeException {}
