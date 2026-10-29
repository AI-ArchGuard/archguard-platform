package io.github.aiarchguard.platform.agent.internal;

import java.time.LocalDate;
import java.util.UUID;

/** 原合成流程的 UTC 日额度端口；同时管理项目和部署预留，与真实调用账本分离，结算必须使用原预留日期。 */
public interface AgentBudgetStore {
    boolean reserve(UUID projectId, LocalDate day, long estimatedMicrousd);
    void settle(UUID projectId, LocalDate day, long reservedMicrousd, long actualMicrousd);
}
