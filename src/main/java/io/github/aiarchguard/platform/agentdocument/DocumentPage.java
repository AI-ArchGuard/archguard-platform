package io.github.aiarchguard.platform.agentdocument;

import java.util.List;

public record DocumentPage(List<DocumentSummary> items, int page, int size, long total) {
    public DocumentPage { items = List.copyOf(items); }
}
