CREATE SCHEMA governance;

CREATE TABLE governance.baseline_scopes (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    rule_set_version_id UUID NOT NULL,
    next_version BIGINT NOT NULL DEFAULT 1,
    active_version_id UUID,
    selection_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_baseline_scope UNIQUE (project_id, repository_id, target_branch, rule_set_version_id),
    CONSTRAINT ck_baseline_scope_counters CHECK (next_version > 0 AND selection_version >= 0)
);

CREATE TABLE governance.baseline_versions (
    id UUID PRIMARY KEY,
    scope_id UUID NOT NULL REFERENCES governance.baseline_scopes(id) ON DELETE RESTRICT,
    version_number BIGINT NOT NULL,
    scan_job_id UUID NOT NULL REFERENCES scanjob.scan_jobs(id) ON DELETE RESTRICT,
    commit_sha VARCHAR(64) NOT NULL,
    report_sha256 CHAR(64) NOT NULL,
    fingerprint_version VARCHAR(32) NOT NULL,
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_baseline_scope_version UNIQUE (scope_id, version_number),
    CONSTRAINT uq_baseline_scope_job UNIQUE (scope_id, scan_job_id),
    CONSTRAINT ck_baseline_commit CHECK (commit_sha ~ '^[0-9a-f]{40}$' OR commit_sha ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_baseline_version CHECK (version_number > 0)
);

ALTER TABLE governance.baseline_scopes ADD CONSTRAINT fk_baseline_active_version
    FOREIGN KEY (active_version_id) REFERENCES governance.baseline_versions(id) ON DELETE RESTRICT;

CREATE TABLE governance.baseline_findings (
    baseline_version_id UUID NOT NULL REFERENCES governance.baseline_versions(id) ON DELETE RESTRICT,
    fingerprint CHAR(64) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    rule_id VARCHAR(160) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    scanner_finding_id VARCHAR(96) NOT NULL,
    PRIMARY KEY (baseline_version_id, fingerprint)
);

CREATE TABLE governance.baseline_selections (
    id UUID PRIMARY KEY,
    scope_id UUID NOT NULL REFERENCES governance.baseline_scopes(id) ON DELETE RESTRICT,
    baseline_version_id UUID NOT NULL REFERENCES governance.baseline_versions(id) ON DELETE RESTRICT,
    selection_version BIGINT NOT NULL,
    selected_by UUID NOT NULL,
    selected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_baseline_selection_version UNIQUE (scope_id, selection_version)
);

CREATE TABLE governance.comparisons (
    id UUID PRIMARY KEY,
    baseline_version_id UUID NOT NULL REFERENCES governance.baseline_versions(id) ON DELETE RESTRICT,
    candidate_job_id UUID NOT NULL REFERENCES scanjob.scan_jobs(id) ON DELETE RESTRICT,
    candidate_report_sha256 CHAR(64) NOT NULL,
    fingerprint_version VARCHAR(32) NOT NULL,
    sealed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_comparison_inputs UNIQUE (baseline_version_id, candidate_job_id)
);

CREATE TABLE governance.comparison_findings (
    comparison_id UUID NOT NULL REFERENCES governance.comparisons(id) ON DELETE RESTRICT,
    classification VARCHAR(16) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    rule_id VARCHAR(160) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    scanner_finding_id VARCHAR(96) NOT NULL,
    PRIMARY KEY (comparison_id, fingerprint),
    CONSTRAINT ck_comparison_classification CHECK (classification IN ('NEW','EXISTING','RESOLVED'))
);

CREATE INDEX idx_baseline_scope_lookup ON governance.baseline_scopes (project_id, repository_id, target_branch, rule_set_version_id);
CREATE INDEX idx_baseline_versions_scope ON governance.baseline_versions (scope_id, version_number DESC);
CREATE INDEX idx_comparisons_candidate ON governance.comparisons (candidate_job_id, created_at DESC);

CREATE FUNCTION governance.reject_immutable_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'governance history is immutable';
END;
$$;

CREATE FUNCTION governance.seal_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NOT OLD.sealed AND NEW.sealed
       AND (to_jsonb(NEW) - 'sealed') = (to_jsonb(OLD) - 'sealed') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'governance history is immutable';
END;
$$;

CREATE FUNCTION governance.require_unsealed_baseline() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM governance.baseline_versions
               WHERE id = NEW.baseline_version_id AND NOT sealed) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'baseline version is sealed';
END;
$$;

CREATE FUNCTION governance.require_unsealed_comparison() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM governance.comparisons WHERE id = NEW.comparison_id AND NOT sealed) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'comparison is sealed';
END;
$$;

CREATE FUNCTION governance.require_valid_active_baseline() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.active_version_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM governance.baseline_versions
        WHERE id = NEW.active_version_id AND scope_id = NEW.id AND sealed
    ) THEN
        RAISE EXCEPTION 'active baseline must be a sealed version in this scope';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER baseline_scopes_active_valid BEFORE UPDATE ON governance.baseline_scopes
    FOR EACH ROW EXECUTE FUNCTION governance.require_valid_active_baseline();
CREATE TRIGGER baseline_versions_immutable BEFORE UPDATE OR DELETE ON governance.baseline_versions
    FOR EACH ROW EXECUTE FUNCTION governance.seal_once();
CREATE TRIGGER baseline_findings_insert_guard BEFORE INSERT ON governance.baseline_findings
    FOR EACH ROW EXECUTE FUNCTION governance.require_unsealed_baseline();
CREATE TRIGGER baseline_findings_immutable BEFORE UPDATE OR DELETE ON governance.baseline_findings
    FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_change();
CREATE TRIGGER baseline_selections_immutable BEFORE UPDATE OR DELETE ON governance.baseline_selections
    FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_change();
CREATE TRIGGER comparisons_immutable BEFORE UPDATE OR DELETE ON governance.comparisons
    FOR EACH ROW EXECUTE FUNCTION governance.seal_once();
CREATE TRIGGER comparison_findings_insert_guard BEFORE INSERT ON governance.comparison_findings
    FOR EACH ROW EXECUTE FUNCTION governance.require_unsealed_comparison();
CREATE TRIGGER comparison_findings_immutable BEFORE UPDATE OR DELETE ON governance.comparison_findings
    FOR EACH ROW EXECUTE FUNCTION governance.reject_immutable_change();
