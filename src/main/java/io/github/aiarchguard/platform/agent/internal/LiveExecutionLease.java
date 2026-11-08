package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.util.UUID;
import java.util.function.LongSupplier;
/** 单次执行的窄记账能力；对象存在不代表已赢得尝试。 */
interface LiveExecutionLease {
 UUID projectId(); UUID requestId();
 LiveAccountingOperations.Admission reserve(LongSupplier remaining);
 LiveAccountingOperations.SendApproval checkBeforeSend(LongSupplier remaining);
 LiveAccountingOperations.Outcome settle(String priceVersion, LiveTokenUsage usage);
 LiveAccountingOperations.Outcome unknown();
 void stop(String code);
 void lockAndValidateSuccess(AgentSnapshot snapshot, LongSupplier remaining);
 void completeSuccess();
}
