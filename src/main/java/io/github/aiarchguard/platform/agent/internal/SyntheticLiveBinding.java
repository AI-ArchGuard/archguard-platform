package io.github.aiarchguard.platform.agent.internal;

import java.time.Instant;
import java.util.UUID;
/** 原始不可变关联的类型包装。 */
record SyntheticLiveBinding(LiveRequestStore.Link link) implements LiveRequestBinding {
 public SyntheticLiveBinding { java.util.Objects.requireNonNull(link); }
 public UUID projectId() { return link.projectId(); }
 public UUID requestId() { return link.requestId(); }
 public Instant createdAt() { return link.createdAt(); }
}
