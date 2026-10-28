package io.github.aiarchguard.platform.agent;

import java.util.List;
import java.util.UUID;

/** 预览清单摘要时选定的模板请求 ID 集；防御性复制保留当前选择，合法性与项目归属在服务端检查。 */
public record LiveBatchPreviewInput(List<UUID> templateRequestIds) {
    public LiveBatchPreviewInput { templateRequestIds = templateRequestIds == null ? null : List.copyOf(templateRequestIds); }
}
