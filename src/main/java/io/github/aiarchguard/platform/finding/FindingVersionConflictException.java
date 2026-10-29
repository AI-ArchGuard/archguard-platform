package io.github.aiarchguard.platform.finding;

/** 人工处置的预期版本未匹配当前行时抛出，由 Web 层映射为 409，提示调用方重读后再决定。 */
public final class FindingVersionConflictException extends RuntimeException { }
