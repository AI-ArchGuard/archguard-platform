package io.github.aiarchguard.platform.agentdocument;

/** 上传或检索输入违反文档边界；公开响应使用固定错误而非原始输入。 */
public final class InvalidDocumentException extends RuntimeException {
    public InvalidDocumentException(String message) { super(message); }
}
