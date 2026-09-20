CREATE SCHEMA project;
CREATE SCHEMA audit;

CREATE TABLE project.projects (
    id UUID PRIMARY KEY,
    project_key VARCHAR(63) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT projects_key_format CHECK (project_key ~ '^[a-z][a-z0-9-]{2,62}$'),
    CONSTRAINT projects_name_not_blank CHECK (length(btrim(name)) > 0)
);

CREATE TABLE project.project_members (
    project_id UUID NOT NULL REFERENCES project.projects(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, actor_id),
    CONSTRAINT project_members_role CHECK (role IN ('MAINTAINER', 'VIEWER'))
);

CREATE INDEX project_members_actor_idx
    ON project.project_members (actor_id, project_id);

CREATE TABLE audit.audit_records (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_id UUID NOT NULL,
    project_id UUID,
    action VARCHAR(80) NOT NULL,
    result VARCHAR(24) NOT NULL,
    trace_id VARCHAR(32) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT audit_records_result CHECK (result IN ('SUCCESS', 'DENIED', 'CONFLICT')),
    CONSTRAINT audit_records_trace_id CHECK (trace_id ~ '^[0-9a-f]{32}$')
);

CREATE INDEX audit_records_project_time_idx
    ON audit.audit_records (project_id, occurred_at DESC);
