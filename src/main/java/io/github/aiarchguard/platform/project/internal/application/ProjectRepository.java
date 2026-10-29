package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.Project;
import io.github.aiarchguard.platform.project.internal.domain.ProjectMember;
import io.github.aiarchguard.platform.project.internal.domain.ProjectName;
import io.github.aiarchguard.platform.project.internal.domain.ProjectRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Project 应用用例需要的持久化端口，只处理本模块的项目和成员事实。
 * JDBC 适配器实现查询与写入；权限决策、事务边界、成功审计由上层应用服务组合。
 * 成员明细和计数方法本身不验证操作者，调用方必须先完成项目授权。
 */
public interface ProjectRepository {
    /** 在调用方事务中创建项目，并把创建者登记为首位维护者。 */
    void insertWithMaintainer(Project project);
    /** 只返回指定操作者作为成员可见的项目；不存在与非成员都返回空。 */
    Optional<Project> findForActor(UUID projectId, UUID actorId);
    /** 读取项目及该操作者的成员角色，供只读授权使用。 */
    Optional<ProjectAccess> findAccessForActor(UUID projectId, UUID actorId);
    /** 查询成员可见性并锁住项目行；调用方事务结束才释放锁，供成员命令串行执行。 */
    Optional<ProjectAccess> lockForActor(UUID projectId, UUID actorId);
    /** 按稳定顺序查询操作者可见的项目页；offset 是记录偏移量。 */
    List<Project> findPageForActor(UUID actorId, int limit, long offset);
    /** 统计同一操作者的可见项目数，与项目分页使用相同的成员范围。 */
    long countForActor(UUID actorId);
    /** 仅在版本匹配时改名并递增版本，返回受影响行数供用例判断冲突。 */
    int updateName(UUID projectId, ProjectName name, long expectedVersion);
    /** 仅删除版本匹配的项目，返回受影响行数；此方法不检查其他模块的内容。 */
    int delete(UUID projectId, long expectedVersion);
    /** 查询已授权项目的成员页，不再接收或验证当前操作者。 */
    List<ProjectMember> findMemberPage(UUID projectId, int limit, long offset);
    /** 统计已授权项目的全部成员，用于成员分页总数。 */
    long countMembers(UUID projectId);
    /** 查询某个目标成员，空值表示该 actor 尚未加入项目。 */
    Optional<ProjectMember> findMember(UUID projectId, UUID actorId);
    /** 不存在则新增成员，已存在则替换角色；最后维护者校验由持锁的上层用例负责。 */
    void upsertMember(UUID projectId, UUID actorId, ProjectRole role, Instant createdAt);
    /** 移除目标成员并返回行数，调用方据此识别成员不存在的情况。 */
    int deleteMember(UUID projectId, UUID actorId);
    /** 统计维护者；用于删除或降级前的判断时，调用方须先持有项目行锁。 */
    long countMaintainers(UUID projectId);
}
