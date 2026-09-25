CREATE TABLE governance.github_repository_links (
    repository_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    provider VARCHAR(16) NOT NULL DEFAULT 'github',
    provider_repository_id VARCHAR(128) NOT NULL UNIQUE,
    owner_name VARCHAR(160) NOT NULL,
    repository_name VARCHAR(160) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_github_link_provider CHECK (provider='github'),
    CONSTRAINT ck_github_external_id CHECK (provider_repository_id ~ '^[1-9][0-9]{0,19}$')
);

CREATE TABLE governance.github_webhook_deliveries (
    delivery_id UUID PRIMARY KEY,
    payload_sha256 CHAR(64) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    action VARCHAR(80),
    provider_repository_id VARCHAR(128),
    project_id UUID,
    repository_id UUID,
    event_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ NOT NULL,
    disposition VARCHAR(24) NOT NULL,
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_github_delivery_disposition CHECK (disposition IN ('RECEIVED','APPLIED','STALE','IGNORED','UNLINKED')),
    CONSTRAINT ck_github_delivery_sealed CHECK ((disposition='RECEIVED')=(NOT sealed))
);

CREATE TABLE governance.github_pull_request_heads (
    project_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    external_id VARCHAR(128) NOT NULL,
    head_sha VARCHAR(64) NOT NULL,
    base_sha VARCHAR(64) NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    event_at TIMESTAMPTZ NOT NULL,
    last_delivery_id UUID NOT NULL REFERENCES governance.github_webhook_deliveries(delivery_id),
    current_gate_id UUID REFERENCES governance.gate_evaluations(id) ON DELETE RESTRICT,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id,repository_id,external_id),
    CONSTRAINT ck_github_pr_head CHECK (head_sha ~ '^[0-9a-f]{40}$' OR head_sha ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_github_pr_base CHECK (base_sha ~ '^[0-9a-f]{40}$' OR base_sha ~ '^[0-9a-f]{64}$')
);

CREATE TABLE governance.report_submissions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    rule_set_version_id UUID NOT NULL,
    provider VARCHAR(16) NOT NULL,
    provider_repository_id VARCHAR(128) NOT NULL,
    commit_sha VARCHAR(64) NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    pull_request_external_id VARCHAR(128),
    pull_request_head_sha VARCHAR(64),
    pull_request_base_sha VARCHAR(64),
    scanner_version VARCHAR(64) NOT NULL,
    schema_version VARCHAR(16) NOT NULL,
    report_sha256 CHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    scan_job_id UUID NOT NULL UNIQUE REFERENCES scanjob.scan_jobs(id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    status VARCHAR(16) NOT NULL,
    gate_evaluation_id UUID REFERENCES governance.gate_evaluations(id) ON DELETE RESTRICT,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_submission_project_key UNIQUE (project_id,idempotency_key),
    CONSTRAINT uq_submission_project_digest UNIQUE (project_id,request_digest),
    CONSTRAINT ck_submission_provider CHECK (provider='github'),
    CONSTRAINT ck_submission_status CHECK (status IN ('RECEIVED','COMPLETED')),
    CONSTRAINT ck_submission_completed CHECK ((status='COMPLETED')=(gate_evaluation_id IS NOT NULL)),
    CONSTRAINT ck_submission_commit CHECK (commit_sha ~ '^[0-9a-f]{40}$' OR commit_sha ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_submission_pr ON governance.report_submissions
    (project_id,repository_id,pull_request_external_id,commit_sha,created_at DESC);

CREATE TRIGGER github_links_immutable BEFORE UPDATE OR DELETE ON governance.github_repository_links
    FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_change();
CREATE FUNCTION governance.finalize_github_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND OLD.disposition='RECEIVED' AND NOT OLD.sealed AND NEW.sealed
       AND NEW.disposition<>'RECEIVED'
       AND (to_jsonb(NEW)-'disposition'-'sealed')=(to_jsonb(OLD)-'disposition'-'sealed') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'webhook delivery is immutable';
END;
$$;
CREATE TRIGGER github_deliveries_immutable BEFORE UPDATE OR DELETE ON governance.github_webhook_deliveries
    FOR EACH ROW EXECUTE FUNCTION governance.finalize_github_delivery();
