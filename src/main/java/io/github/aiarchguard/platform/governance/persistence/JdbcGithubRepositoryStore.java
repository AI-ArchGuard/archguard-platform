package io.github.aiarchguard.platform.governance.persistence;

import io.github.aiarchguard.platform.governance.GithubRepositoryLinkView;
import io.github.aiarchguard.platform.governance.internal.GithubRepositoryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Platform 仓库与 GitHub 数字仓库 ID 的持久化绑定；名称仅作展示。
 * 本适配器按标识查询和插入，项目成员权限由上层应用服务验证。
 */
@Repository
public class JdbcGithubRepositoryStore implements GithubRepositoryStore {
    private final JdbcClient jdbc;
    public JdbcGithubRepositoryStore(JdbcClient jdbc) { this.jdbc = jdbc; }
    /** 在项目和 Platform 仓库范围内读取绑定。 */
    @Override public Optional<GithubRepositoryLinkView> find(UUID projectId, UUID repositoryId) {
        return jdbc.sql("SELECT * FROM governance.github_repository_links WHERE project_id=:project AND repository_id=:repository")
            .param("project", projectId).param("repository", repositoryId)
            .query(JdbcGithubRepositoryStore::map).optional();
    }
    /** Webhook 按 GitHub 数字 ID 找已登记绑定；此查询不从展示名称猜测身份。 */
    @Override public Optional<GithubRepositoryLinkView> byExternalId(String externalId) {
        return jdbc.sql("SELECT * FROM governance.github_repository_links WHERE provider_repository_id=:external")
            .param("external", externalId).query(JdbcGithubRepositoryStore::map).optional();
    }
    /** 借唯一约束和 ON CONFLICT 处理并发绑定竞争，交由上层判断重放或冲突。 */
    @Override public boolean insert(GithubRepositoryLinkView value) {
        return jdbc.sql("""
            INSERT INTO governance.github_repository_links(repository_id,project_id,provider,
              provider_repository_id,owner_name,repository_name,created_by,created_at)
            VALUES (:repository,:project,'github',:external,:owner,:name,:actor,:at)
            ON CONFLICT DO NOTHING
            """).param("repository", value.repositoryId()).param("project", value.projectId())
            .param("external", value.providerRepositoryId()).param("owner", value.ownerName())
            .param("name", value.repositoryName()).param("actor", value.createdBy())
            .param("at", Timestamp.from(value.createdAt())).update() == 1;
    }
    private static GithubRepositoryLinkView map(ResultSet r, int row) throws SQLException {
        return new GithubRepositoryLinkView(r.getObject("project_id", UUID.class),
            r.getObject("repository_id", UUID.class), r.getString("provider"),
            r.getString("provider_repository_id"), r.getString("owner_name"),
            r.getString("repository_name"), r.getObject("created_by", UUID.class),
            r.getTimestamp("created_at").toInstant());
    }
}
