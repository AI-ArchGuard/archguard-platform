package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.common.TraceIdFilter;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 用真实 Spring HTTP 处理链和独立的 PostgreSQL 测试容器，验证 Project 的对外行为。
 * 测试身份由 Spring Security 测试工具注入，不表示生产环境可以用用户名代替 OIDC 验签。
 * 同时查询测试库确认成员关系、并发冲突和审计结果，不连接本地部署中的业务数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProjectApiIntegrationTest extends PostgresIntegrationTestSupport {
    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ACTOR = "22222222-2222-2222-2222-222222222222";
    private static final String THIRD_ACTOR = "33333333-3333-3333-3333-333333333333";
    private static final String VALID_TRACE_ID = "0123456789abcdef0123456789abcdef";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    @Autowired
    ProjectApiIntegrationTest(MockMvc mockMvc, ObjectMapper objectMapper, JdbcClient jdbcClient) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
    }

    /** 未认证读取返回统一 401，响应头和错误正文复用合法的传入 traceId。 */
    @Test
    void requiresAuthenticationWithAUniformTraceableError() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{id}", UUID.randomUUID())
                .header(TraceIdFilter.HEADER_NAME, VALID_TRACE_ID))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(TraceIdFilter.HEADER_NAME, VALID_TRACE_ID))
            .andExpect(jsonPath("$.code").value("authentication.required"))
            .andExpect(jsonPath("$.traceId").value(VALID_TRACE_ID))
            .andExpect(jsonPath("$.details").isMap());
    }

    /** 创建接口同样受认证保护，未认证 POST 不能进入正常创建成功路径。 */
    @Test
    void unauthenticatedCreationIsRejectedBeforeBusinessHandling() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(projectJson(uniqueKey(), "Unauthenticated")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("authentication.required"))
            .andExpect(jsonPath("$.traceId").isString());
    }

    /** 非规范请求关联 ID 必须被替换，错误正文与响应头仍指向同一个新 ID。 */
    @Test
    void replacesAnInvalidInboundTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/projects/{id}", UUID.randomUUID())
                .header(TraceIdFilter.HEADER_NAME, "not-valid"))
            .andExpect(status().isUnauthorized())
            .andReturn();

        String responseTraceId = result.getResponse().getHeader(TraceIdFilter.HEADER_NAME);
        assertThat(responseTraceId).matches("[0-9a-f]{32}").isNotEqualTo("not-valid");
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("traceId").asText())
            .isEqualTo(responseTraceId);
    }

    /** “已登录”不等于有创建权限：缺少 project:create 时拒绝，并保留 DENIED 审计。 */
    @Test
    void deniesCreationWithoutPermissionAndRecordsTheDecision() throws Exception {
        String key = uniqueKey();
        mockMvc.perform(post("/api/v1/projects")
                .with(csrf())
                .with(user(ACTOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(projectJson(key, "Denied")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("authorization.denied"));

        assertThat(auditCount(key, "DENIED")).isOne();
    }

    /** 无效 key/name 返回字段级校验信息，而不是把输入交给数据库产生内部错误。 */
    @Test
    void validatesRequestsBeforeCallingTheApplicationBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                .with(csrf())
                .with(creator(ACTOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(projectJson("BAD", "")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation.failed"))
            .andExpect(jsonPath("$.details.fields.key").exists())
            .andExpect(jsonPath("$.details.fields.name").exists());
    }

    /** 成功创建应同时得到项目、创建者的维护者关系和成功审计，并允许该成员查询。 */
    @Test
    void createsAProjectMembershipAndSuccessAuditThenReadsItForTheMember() throws Exception {
        String key = uniqueKey();
        MvcResult creation = createProject(key, "  Platform Café  ")
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                "/api/v1/projects/[0-9a-f-]{36}")))
            .andExpect(jsonPath("$.key").value(key))
            .andExpect(jsonPath("$.name").value("Platform Café"))
            .andExpect(jsonPath("$.version").value(0))
            .andReturn();

        JsonNode body = objectMapper.readTree(creation.getResponse().getContentAsString());
        UUID projectId = UUID.fromString(body.get("id").asText());
        assertThat(memberCount(projectId, UUID.fromString(ACTOR))).isOne();
        assertThat(auditCount(key, "SUCCESS")).isOne();

        mockMvc.perform(get("/api/v1/projects/{id}", projectId).with(user(ACTOR)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(projectId.toString()))
            .andExpect(jsonPath("$.key").value(key));
    }

    /** 重复 key 使用稳定的 409 错误；成功与冲突两次尝试分别留下审计。 */
    @Test
    void mapsDuplicateKeysToConflictAndRecordsTheFailedAttempt() throws Exception {
        String key = uniqueKey();
        createProject(key, "First").andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/projects")
                .with(csrf())
                .with(creator(OTHER_ACTOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(projectJson(key, "Second")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("project.key_conflict"))
            .andExpect(jsonPath("$.message").value("The project key is already in use."));

        assertThat(auditCount(key, "SUCCESS")).isOne();
        assertThat(auditCount(key, "CONFLICT")).isOne();
    }

    /** 两个创建者同时争用同一 key，由数据库唯一约束裁决，只能留下一条项目记录。 */
    @Test
    void databaseUniquenessResolvesConcurrentProjectCreation() throws Exception {
        String key = uniqueKey();
        String body = projectJson(key, "Concurrent");
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Integer> first = concurrentCreation(barrier, ACTOR, body);
        Callable<Integer> second = concurrentCreation(barrier, OTHER_ACTOR, body);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(java.util.List.of(first, second)).stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .sorted()
                .toList();
            assertThat(results).containsExactly(201, 409);
        }

        assertThat(projectCount(key)).isOne();
        assertThat(auditCount(key, "SUCCESS")).isOne();
        assertThat(auditCount(key, "CONFLICT")).isOne();
    }

    /** 对非成员而言，已有项目和随机不存在的项目必须呈现相同的 404/错误码。 */
    @Test
    void concealsExistingProjectsFromNonMembers() throws Exception {
        String key = uniqueKey();
        MvcResult creation = createProject(key, "Private").andExpect(status().isCreated()).andReturn();
        UUID projectId = UUID.fromString(objectMapper.readTree(
            creation.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/projects/{id}", projectId).with(user(OTHER_ACTOR)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("project.not_found"));
        mockMvc.perform(get("/api/v1/projects/{id}", UUID.randomUUID()).with(user(OTHER_ACTOR)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("project.not_found"));
    }

    /** 列表也必须按成员身份隔离：既包含自己的项目，又不能混入另一调用者的项目。 */
    @Test
    void listsOnlyProjectsVisibleToTheCurrentActor() throws Exception {
        String ownKey = uniqueKey();
        String otherKey = uniqueKey();
        createProject(ownKey, "Own project").andExpect(status().isCreated());
        createProjectAs(OTHER_ACTOR, otherKey, "Other project").andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/v1/projects")
                .with(user(ACTOR))
                .queryParam("page", "0")
                .queryParam("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(100))
            .andExpect(jsonPath("$.total").isNumber())
            .andReturn();

        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        assertThat(items).anyMatch(item -> item.get("key").asText().equals(ownKey));
        assertThat(items).noneMatch(item -> item.get("key").asText().equals(otherKey));
    }

    /** 超出上限的单页大小属于错误请求，不通过静默截断掩盖客户端的参数问题。 */
    @Test
    void validatesPaginationParameters() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                .with(user(ACTOR))
                .queryParam("size", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("request.malformed"));
    }

    /** 修改会递增版本；旧版本写入应冲突，而 VIEWER 即使提供新版本也无写权限。 */
    @Test
    void updatesAProjectWithOptimisticConcurrencyAndMaintainerAuthorization() throws Exception {
        UUID projectId = createdProjectId(createProject(uniqueKey(), "Before"));

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                .with(user(ACTOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson("After", 0)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("After"))
            .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                .with(user(ACTOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson("Stale", 0)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("project.version_conflict"));

        setMember(projectId, OTHER_ACTOR, "VIEWER").andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                .with(user(OTHER_ACTOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson("Forbidden", 1)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("authorization.denied"));
    }

    /** 删除同时受角色和版本约束；删除成功后项目不可读，但删除审计仍应存在。 */
    @Test
    void deletesAProjectAtTheExpectedVersionAndRetainsItsAuditRecord() throws Exception {
        UUID projectId = createdProjectId(createProject(uniqueKey(), "Delete me"));
        setMember(projectId, OTHER_ACTOR, "VIEWER").andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/projects/{id}", projectId)
                .with(user(OTHER_ACTOR))
                .queryParam("version", "0"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("authorization.denied"));

        mockMvc.perform(patch("/api/v1/projects/{id}", projectId)
                .with(user(ACTOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson("Delete me now", 0)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(delete("/api/v1/projects/{id}", projectId)
                .with(user(ACTOR))
                .queryParam("version", "0"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("project.version_conflict"));

        mockMvc.perform(delete("/api/v1/projects/{id}", projectId)
                .with(user(ACTOR))
                .queryParam("version", "1"))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/projects/{id}", projectId).with(user(ACTOR)))
            .andExpect(status().isNotFound());
        assertThat(projectExists(projectId)).isFalse();
        assertThat(auditActionCount(projectId, "project.delete", "SUCCESS")).isOne();
    }

    /** 覆盖成员增改删、查看者不可授权及最后维护者保护，避免项目失去维护入口。 */
    @Test
    void managesMembersWhilePreservingAtLeastOneMaintainer() throws Exception {
        UUID projectId = createdProjectId(createProject(uniqueKey(), "Members"));

        setMember(projectId, OTHER_ACTOR, "VIEWER")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.actorId").value(OTHER_ACTOR))
            .andExpect(jsonPath("$.role").value("VIEWER"));

        mockMvc.perform(get("/api/v1/projects/{id}/members", projectId)
                .with(user(OTHER_ACTOR)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2));

        mockMvc.perform(put("/api/v1/projects/{id}/members/{actorId}", projectId, THIRD_ACTOR)
                .with(user(OTHER_ACTOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(memberJson("VIEWER")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("authorization.denied"));

        setMember(projectId, OTHER_ACTOR, "MAINTAINER")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MAINTAINER"));

        mockMvc.perform(delete("/api/v1/projects/{id}/members/{actorId}", projectId, OTHER_ACTOR)
                .with(user(ACTOR)))
            .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/projects/{id}/members/{actorId}", projectId, OTHER_ACTOR)
                .with(user(ACTOR)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("project.member_not_found"));

        mockMvc.perform(delete("/api/v1/projects/{id}/members/{actorId}", projectId, ACTOR)
                .with(user(ACTOR)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("project.last_maintainer"));
    }

    /** 默认使用固定测试创建者；不同身份的隔离场景则显式调用 createProjectAs。 */
    private org.springframework.test.web.servlet.ResultActions createProject(String key, String name) throws Exception {
        return createProjectAs(ACTOR, key, name);
    }

    private org.springframework.test.web.servlet.ResultActions createProjectAs(String actorId, String key, String name)
            throws Exception {
        return mockMvc.perform(post("/api/v1/projects")
            .with(csrf())
            .with(creator(actorId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(projectJson(key, name)));
    }

    private org.springframework.test.web.servlet.ResultActions setMember(UUID projectId, String actorId, String role)
            throws Exception {
        return mockMvc.perform(put("/api/v1/projects/{id}/members/{actorId}", projectId, actorId)
            .with(user(ACTOR))
            .contentType(MediaType.APPLICATION_JSON)
            .content(memberJson(role)));
    }

    /** 只在测试安全上下文中授予窄权限，不能作为生产认证或角色管理的实现。 */
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor
            creator(String actorId) {
        return user(actorId).authorities(new SimpleGrantedAuthority("project:create"));
    }

    /** 屏障让两次请求尽量同时进入创建流程，检验竞争而非顺序调用的重复判断。 */
    private Callable<Integer> concurrentCreation(CyclicBarrier barrier, String actorId, String body) {
        return () -> {
            barrier.await();
            return mockMvc.perform(post("/api/v1/projects")
                    .with(csrf())
                    .with(creator(actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
        };
    }

    private String projectJson(String key, String name) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("key", key, "name", name));
    }

    private String updateJson(String name, long version) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("name", name, "version", version));
    }

    private String memberJson(String role) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("role", role));
    }

    private UUID createdProjectId(org.springframework.test.web.servlet.ResultActions creation) throws Exception {
        MvcResult result = creation.andExpect(status().isCreated()).andReturn();
        return UUID.fromString(objectMapper.readTree(
            result.getResponse().getContentAsString()).get("id").asText());
    }

    private static String uniqueKey() {
        return "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 直接核对测试库中的维护者关系，避免只凭 HTTP 响应推断持久化已成功。 */
    private int memberCount(UUID projectId, UUID actorId) {
        return jdbcClient.sql("""
                SELECT count(*) FROM project.project_members
                WHERE project_id = :projectId AND actor_id = :actorId AND role = 'MAINTAINER'
                """)
            .param("projectId", projectId)
            .param("actorId", actorId)
            .query(Integer.class)
            .single();
    }

    /** 创建类审计携带受控 projectKey，以此把同一合成项目的尝试与审计关联起来。 */
    private int auditCount(String projectKey, String result) {
        return jdbcClient.sql("""
                SELECT count(*) FROM audit.audit_records
                WHERE metadata ->> 'projectKey' = :projectKey AND result = :result
                """)
            .param("projectKey", projectKey)
            .param("result", result)
            .query(Integer.class)
            .single();
    }


    private int projectCount(String projectKey) {
        return jdbcClient.sql("SELECT count(*) FROM project.projects WHERE project_key = :projectKey")
            .param("projectKey", projectKey)
            .query(Integer.class)
            .single();
    }

    private boolean projectExists(UUID projectId) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM project.projects WHERE id = :projectId)")
            .param("projectId", projectId)
            .query(Boolean.class)
            .single();
    }

    /** 项目删除后仍能按稳定 UUID 查审计，证明审计不随项目行被级联删除。 */
    private int auditActionCount(UUID projectId, String action, String result) {
        return jdbcClient.sql("""
                SELECT count(*) FROM audit.audit_records
                WHERE project_id = :projectId AND action = :action AND result = :result
                """)
            .param("projectId", projectId)
            .param("action", action)
            .param("result", result)
            .query(Integer.class)
            .single();
    }
}
