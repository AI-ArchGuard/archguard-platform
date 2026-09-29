package io.github.aiarchguard.platform.agentdocument;

import java.util.List;

public record DocumentVersionPage(List<DocumentVersionSummary> items, int page, int size, long total) {
    public DocumentVersionPage { items = List.copyOf(items); }
}
