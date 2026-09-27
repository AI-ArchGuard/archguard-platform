CREATE TABLE governance.github_pr_head_revisions (
    delivery_id UUID PRIMARY KEY REFERENCES governance.github_webhook_deliveries(delivery_id),
    project_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    head_sha VARCHAR(64) NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    event_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_github_revision_head CHECK (head_sha ~ '^[0-9a-f]{40}$' OR head_sha ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_github_pr_head_revisions ON governance.github_pr_head_revisions
    (project_id,repository_id,external_id,event_at DESC);

CREATE TRIGGER github_pr_head_revisions_immutable
    BEFORE UPDATE OR DELETE ON governance.github_pr_head_revisions
    FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_change();
