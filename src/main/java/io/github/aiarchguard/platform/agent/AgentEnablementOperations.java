package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/**
 * 项目 Agent 设置与个人确认的应用边界。写入需先核验 owner、项目角色、Origin 与传输条件；
 * 确认记录本身不等于发送或费用许可。
 */
public interface AgentEnablementOperations {
    /** 在读取写入正文之前验证项目角色、owner 及浏览器来源/传输条件；内部更新方法仍会核验业务身份与版本。 */
    void authorizeWrite(UUID projectId, String origin, boolean secure);
    AgentSettingsView settings(UUID projectId);
    AgentSettingsView update(UUID projectId, long expectedRevision, AgentSettingsUpdate request);
    PersonalEnablementView approve(UUID projectId, PersonalEnablementInput request);
    PersonalEnablementView get(UUID projectId, UUID enablementId);
    PersonalEnablementView revoke(UUID projectId, UUID enablementId);
}
