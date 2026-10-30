package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.transaction.annotation.Transactional;

/** RED 编译接缝：未实现授权逻辑一律拒绝；不注册生产运行时。 */
public class DailyAgentControlService {
    DailyAgentControlService(DailyRuntimePolicy policy) { }
    public void authorizeControlWrite(UUID project, String origin, boolean secure) { throw unavailable(); }
    @Transactional(readOnly = true) public DailyContext context(UUID project) { throw unavailable(); }
    @Transactional(readOnly = true) public DailySettings settings(UUID project) { throw unavailable(); }
    @Transactional public DailySettings updateSettings(UUID project, long revision, DailySettingsInput input) { throw unavailable(); }
    @Transactional public Creation<DailyRiskView> acknowledge(UUID project, String key, DailyRiskInput input) { throw unavailable(); }
    @Transactional(readOnly = true) public DailyRiskView getRisk(UUID project, UUID risk) { throw unavailable(); }
    @Transactional public DailyRiskView revokeRisk(UUID project, UUID risk) { throw unavailable(); }
    @Transactional public Creation<DailyGrantView> approveGrant(UUID project, String key, DailyGrantInput input) { throw unavailable(); }
    @Transactional(readOnly = true) public DailyGrantView getGrant(UUID project, UUID grant) { throw unavailable(); }
    @Transactional public DailyGrantView revokeGrant(UUID project, UUID grant) { throw unavailable(); }
    /** 后续实现仅参与调用者已有的有界事务，不能另开 REQUIRES_NEW 事务脱离锁。 */
    public DailyAgentStore.Grant requireCurrent(UUID project, UUID grant, String purpose, LongSupplier remaining) { throw unavailable(); }
    private static AgentUnavailableException unavailable() { return new AgentUnavailableException(); }
}
