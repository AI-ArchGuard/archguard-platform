package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.project.ProjectAccessView;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 验证批次执行只读服务的授权顺序和依赖边界：先 Project Viewer，再 Schema，再精确批次读取。使用调用记录替身，不涉及凭据、来源准入或模型。
 */
class LiveBatchExecutionServiceTest {
    private final UUID project = UUID.randomUUID(), batch = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private boolean denied, unavailable;
    private final ProjectAuthorization projects = new ProjectAuthorization() {
        @Override public ProjectAccessView requireViewer(UUID id) {
            assertThat(id).isEqualTo(project); calls.add("viewer");
            if (denied) throw new ProjectNotFoundException();
            return null;
        }
        @Override public ProjectAccessView requireMaintainer(UUID id) { throw new AssertionError("Read must not escalate"); }
        @Override public ProjectAccessView requireViewerForActor(UUID id, UUID actor) { throw new AssertionError("Use current actor"); }
    };
    private final AgentSchemaAvailability schema = new AgentSchemaAvailability(() -> {}) {
        @Override public void requireReady() {
            calls.add("schema"); if (unavailable) throw new AgentUnavailableException();
        }
    };
    private final LiveBatchExecutionStore store = (projectId, batchId) -> {
        assertThat(projectId).isEqualTo(project); assertThat(batchId).isEqualTo(batch);
        calls.add("load"); return Optional.empty();
    };
    private final LiveBatchExecutionService service = new LiveBatchExecutionService(projects, schema, store);

    // 授权失败必须先返回，不能泄漏 Schema 或批次存在性。
    @Test void projectAuthorizationPrecedesSchemaAndAnyBatchRead() {
        denied = true;
        assertThatThrownBy(() -> service.get(project, batch)).isInstanceOf(ProjectNotFoundException.class);
        assertThat(calls).containsExactly("viewer");
    }

    // 不可用时只失败，不在 GET 中尝试修复历史。
    @Test void unavailableSchemaNeverAttemptsToRepairOrReadHistory() {
        unavailable = true;
        assertThatThrownBy(() -> service.get(project, batch)).isInstanceOf(AgentUnavailableException.class);
        assertThat(calls).containsExactly("viewer", "schema");
    }

    @Test void missingExactProjectBatchIsHiddenAndReadDoesNotEscalateToMaintainer() {
        assertThatThrownBy(() -> service.get(project, batch)).isInstanceOf(AgentNotFoundException.class);
        assertThat(calls).containsExactly("viewer", "schema", "load");
    }

    // 通过构造器与接口形状锁定只读边界，避免历史读取隐式获得发送能力。
    @Test void historicalReaderHasNoCredentialSourceInventoryAdmissionOrModelDependencies() {
        assertThat(Arrays.asList(LiveBatchExecutionService.class.getDeclaredConstructors()[0].getParameterTypes()))
            .containsExactly(ProjectAuthorization.class, AgentSchemaAvailability.class, LiveBatchExecutionStore.class);
        assertThat(Arrays.asList(io.github.aiarchguard.platform.agent.LiveBatchExecutionOperations.class.getDeclaredMethods()))
            .hasSize(1).allSatisfy(method -> assertThat(method.getName()).isEqualTo("get"));
    }
}
