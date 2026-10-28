package io.github.aiarchguard.platform.agentdocument;

/** 上传应用结果；replay 区分幂等重放与新版本创建，供入口选择 200 或 201。 */
public record DocumentUploadResult(DocumentVersionView version, boolean replay) {}
