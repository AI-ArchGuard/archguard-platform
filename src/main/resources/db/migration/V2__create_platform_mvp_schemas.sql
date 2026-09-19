CREATE SCHEMA repository;
CREATE SCHEMA ruleset;
CREATE SCHEMA scanjob;
CREATE SCHEMA finding;

CREATE TABLE repository.repositories (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    repository_key VARCHAR(63) NOT NULL,
    name VARCHAR(120) NOT NULL,
    mount_path VARCHAR(1024) NOT NULL,
    scanner_identity VARCHAR(160) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_repository_project_key UNIQUE (project_id, repository_key),
    CONSTRAINT uq_repository_identity UNIQUE (scanner_identity),
    CONSTRAINT ck_repository_key CHECK (repository_key ~ '^[a-z][a-z0-9-]{2,62}$'),
    CONSTRAINT ck_repository_version CHECK (version >= 0)
);

CREATE INDEX idx_repository_project ON repository.repositories (project_id, created_at DESC, id DESC);

CREATE TABLE ruleset.rule_sets (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    rule_set_key VARCHAR(63) NOT NULL,
    name VARCHAR(120) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ruleset_repository_key UNIQUE (repository_id, rule_set_key),
    CONSTRAINT ck_ruleset_key CHECK (rule_set_key ~ '^[a-z][a-z0-9-]{2,62}$')
);

CREATE TABLE ruleset.rule_set_versions (
    id UUID PRIMARY KEY,
    rule_set_id UUID NOT NULL REFERENCES ruleset.rule_sets(id) ON DELETE RESTRICT,
    version_number INTEGER NOT NULL,
    yaml_content TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    scanner_version VARCHAR(32) NOT NULL,
    rules_schema_version VARCHAR(16) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ruleset_version UNIQUE (rule_set_id, version_number),
    CONSTRAINT ck_ruleset_content_size CHECK (octet_length(yaml_content) BETWEEN 1 AND 1048576),
    CONSTRAINT ck_ruleset_version_number CHECK (version_number > 0)
);

CREATE INDEX idx_ruleset_project_repository ON ruleset.rule_sets (project_id, repository_id, created_at DESC, id DESC);

CREATE TABLE scanjob.scan_jobs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    rule_set_version_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    outcome VARCHAR(32),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    attempt INTEGER NOT NULL DEFAULT 0,
    attempt_token UUID,
    version BIGINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(80),
    failure_message VARCHAR(512),
    report_bytes BYTEA,
    report_sha256 CHAR(64),
    report_partial BOOLEAN NOT NULL DEFAULT FALSE,
    scanner_version VARCHAR(32),
    result_schema_version VARCHAR(16),
    CONSTRAINT uq_scanjob_idempotency UNIQUE (project_id, idempotency_key),
    CONSTRAINT ck_scanjob_status CHECK (status IN ('QUEUED','RUNNING','CANCEL_REQUESTED','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT ck_scanjob_outcome CHECK (outcome IS NULL OR outcome IN ('PASS','VIOLATION')),
    CONSTRAINT ck_scanjob_attempt CHECK (attempt >= 0),
    CONSTRAINT ck_scanjob_version CHECK (version >= 0)
);

CREATE INDEX idx_scanjob_project_created ON scanjob.scan_jobs (project_id, created_at DESC, id DESC);
CREATE INDEX idx_scanjob_claim ON scanjob.scan_jobs (status, created_at, id);

CREATE TABLE finding.evidences (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    job_id UUID NOT NULL,
    scanner_evidence_id VARCHAR(96) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    summary VARCHAR(1024) NOT NULL,
    path VARCHAR(4096) NOT NULL,
    start_line INTEGER NOT NULL,
    start_column INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    end_column INTEGER NOT NULL,
    CONSTRAINT uq_evidence_job_scanner_id UNIQUE (job_id, scanner_evidence_id)
);

CREATE TABLE finding.findings (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    job_id UUID NOT NULL,
    scanner_finding_id VARCHAR(96) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    rule_id VARCHAR(160) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    subject_id VARCHAR(96) NOT NULL,
    message VARCHAR(1024) NOT NULL,
    path VARCHAR(4096),
    start_line INTEGER,
    start_column INTEGER,
    end_line INTEGER,
    end_column INTEGER,
    disposition VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_finding_job_scanner_id UNIQUE (job_id, scanner_finding_id),
    CONSTRAINT ck_finding_disposition CHECK (disposition IN ('OPEN','FALSE_POSITIVE','RISK_ACCEPTED')),
    CONSTRAINT ck_finding_version CHECK (version >= 0)
);

CREATE TABLE finding.finding_evidences (
    finding_id UUID NOT NULL REFERENCES finding.findings(id) ON DELETE CASCADE,
    evidence_id UUID NOT NULL REFERENCES finding.evidences(id) ON DELETE CASCADE,
    PRIMARY KEY (finding_id, evidence_id)
);

CREATE TABLE finding.disposition_history (
    id UUID PRIMARY KEY,
    finding_id UUID NOT NULL REFERENCES finding.findings(id) ON DELETE RESTRICT,
    previous_disposition VARCHAR(32) NOT NULL,
    new_disposition VARCHAR(32) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    actor_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL
);

CREATE INDEX idx_finding_job ON finding.findings (job_id, severity DESC, id);
CREATE INDEX idx_disposition_finding ON finding.disposition_history (finding_id, occurred_at, id);
