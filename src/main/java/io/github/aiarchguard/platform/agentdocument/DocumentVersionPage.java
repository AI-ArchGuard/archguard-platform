package io.github.aiarchguard.platform.agentdocument;

import java.util.List;

/** 按版本号倒序返回的不可变版本元数据页，复制集合防止外部改动。 */
public record DocumentVersionPage(List<DocumentVersionSummary> items, int page, int size, long total) {
    public DocumentVersionPage { items = List.copyOf(items); }
}
