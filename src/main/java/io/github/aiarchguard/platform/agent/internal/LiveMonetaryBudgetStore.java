package io.github.aiarchguard.platform.agent.internal;

import java.time.LocalDate;
import java.util.UUID;

/** 两种模式共享日金额，读写均在调用方事务中执行。 */
public interface LiveMonetaryBudgetStore {
    record MoneyUsage(long reserved, long spent) {}
    MoneyUsage lock(String scope, UUID id, LocalDate day);
    void reserve(String scope, UUID id, LocalDate day, long reserved);
    void settle(String scope, UUID id, LocalDate day, long reserved, long actual);
}
