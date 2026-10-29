package io.github.aiarchguard.platform.agentdocument;

/** 文档或版本不在指定项目中；统一缺失语义避免泄露跨项目资源。 */
public final class DocumentNotFoundException extends RuntimeException {}
