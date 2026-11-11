package io.github.aiarchguard.platform.agent.internal;

import java.time.Instant;
import java.util.UUID;
/** 精确关联不伪造批次和模板。 */
sealed interface LiveRequestBinding permits SyntheticLiveBinding, DailyLiveBinding {
 UUID projectId(); UUID requestId(); Instant createdAt();
}
