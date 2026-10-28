package io.github.aiarchguard.platform.agentdocument;

import java.util.List;

/** 文档元数据分页，复制条目集合以保持响应快照，不包含正文。 */
public record DocumentPage(List<DocumentSummary> items, int page, int size, long total) {
    public DocumentPage { items = List.copyOf(items); }
}
