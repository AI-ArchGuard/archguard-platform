CREATE TABLE governance.policy_exceptions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    rule_set_version_id UUID NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    scope_value VARCHAR(160) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_exception_scope CHECK (scope_type IN ('FINGERPRINT','RULE')),
    CONSTRAINT ck_exception_expiry CHECK (expires_at > effective_at),
    CONSTRAINT ck_exception_reason CHECK (length(btrim(reason)) >= 10)
);

CREATE INDEX idx_exception_scope ON governance.policy_exceptions
    (project_id, repository_id, target_branch, rule_set_version_id, expires_at);

CREATE TABLE governance.policy_exception_revocations (
    exception_id UUID PRIMARY KEY REFERENCES governance.policy_exceptions(id) ON DELETE RESTRICT,
    version_id UUID NOT NULL UNIQUE,
    reason VARCHAR(1000) NOT NULL,
    revoked_by UUID NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_revocation_reason CHECK (length(btrim(reason)) >= 10)
);

CREATE TABLE governance.gate_evaluations (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    rule_set_version_id UUID NOT NULL,
    candidate_job_id UUID NOT NULL REFERENCES scanjob.scan_jobs(id) ON DELETE RESTRICT,
    comparison_id UUID REFERENCES governance.comparisons(id) ON DELETE RESTRICT,
    baseline_version_id UUID REFERENCES governance.baseline_versions(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    outcome VARCHAR(8) NOT NULL,
    ci_exit_code INTEGER NOT NULL,
    error_kind VARCHAR(32),
    error_code VARCHAR(100),
    policy_version VARCHAR(32) NOT NULL,
    fingerprint_version VARCHAR(32) NOT NULL,
    new_count INTEGER NOT NULL,
    existing_count INTEGER NOT NULL,
    resolved_count INTEGER NOT NULL,
    blocked_count INTEGER NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_gate_project_key UNIQUE (project_id, idempotency_key),
    CONSTRAINT ck_gate_outcome CHECK (outcome IN ('PASS','FAIL','ERROR')),
    CONSTRAINT ck_gate_exit CHECK ((outcome='PASS' AND ci_exit_code=0) OR
        (outcome='FAIL' AND ci_exit_code=2) OR
        (outcome='ERROR' AND ci_exit_code IN (64,70))),
    CONSTRAINT ck_gate_error CHECK ((outcome='ERROR' AND error_kind IS NOT NULL AND error_code IS NOT NULL)
        OR (outcome<>'ERROR' AND error_kind IS NULL AND error_code IS NULL)),
    CONSTRAINT ck_gate_counts CHECK (new_count>=0 AND existing_count>=0 AND resolved_count>=0 AND blocked_count>=0)
);

CREATE TABLE governance.gate_exception_hits (
    gate_evaluation_id UUID NOT NULL REFERENCES governance.gate_evaluations(id) ON DELETE RESTRICT,
    exception_id UUID NOT NULL REFERENCES governance.policy_exceptions(id) ON DELETE RESTRICT,
    exception_version_id UUID NOT NULL,
    PRIMARY KEY (gate_evaluation_id, exception_id)
);

CREATE INDEX idx_gate_scope_time ON governance.gate_evaluations
    (project_id, repository_id, target_branch, rule_set_version_id, evaluated_at DESC);

CREATE FUNCTION governance.require_unsealed_gate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM governance.gate_evaluations WHERE id=NEW.gate_evaluation_id AND NOT sealed) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'gate evaluation is sealed';
END;
$$;

CREATE TRIGGER policy_exceptions_immutable BEFORE UPDATE OR DELETE ON governance.policy_exceptions
    FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_change();
CREATE TRIGGER policy_exception_revocations_immutable BEFORE UPDATE OR DELETE ON governance.policy_exception_revocations
    FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_change();
CREATE TRIGGER gate_evaluations_immutable BEFORE UPDATE OR DELETE ON governance.gate_evaluations
    FOR EACH ROW EXECUTE FUNCTION governance.seal_once();
CREATE TRIGGER gate_exception_hits_insert_guard BEFORE INSERT ON governance.gate_exception_hits
    FOR EACH ROW EXECUTE FUNCTION governance.require_unsealed_gate();
CREATE TRIGGER gate_exception_hits_immutable BEFORE UPDATE OR DELETE ON governance.gate_exception_hits
    FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_change();
