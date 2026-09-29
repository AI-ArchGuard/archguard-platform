CREATE SCHEMA agent_document;

CREATE TABLE agent_document.upload_requests (
    project_id UUID NOT NULL REFERENCES project.projects(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    version_id UUID,
    PRIMARY KEY (project_id, idempotency_key),
    CONSTRAINT upload_requests_hash CHECK (request_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TABLE agent_document.documents (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project.projects(id) ON DELETE RESTRICT,
    document_key VARCHAR(63) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT documents_project_key UNIQUE (project_id, document_key),
    CONSTRAINT documents_project_id UNIQUE (project_id, id),
    CONSTRAINT documents_key_format CHECK (document_key ~ '^[a-z][a-z0-9-]{2,62}$')
);

CREATE INDEX documents_project_idx ON agent_document.documents (project_id, created_at DESC, id DESC);

CREATE TABLE agent_document.document_versions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    project_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    media_type VARCHAR(32) NOT NULL,
    content_text TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    byte_size INTEGER NOT NULL,
    fragment_count INTEGER NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT document_versions_number UNIQUE (document_id, version_number),
    CONSTRAINT document_versions_project_id UNIQUE (project_id, id),
    CONSTRAINT document_versions_document_project FOREIGN KEY (project_id, document_id)
        REFERENCES agent_document.documents(project_id, id) ON DELETE RESTRICT,
    CONSTRAINT document_versions_media_type CHECK (media_type IN ('text/markdown', 'text/plain')),
    CONSTRAINT document_versions_sha CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT document_versions_size CHECK (byte_size BETWEEN 1 AND 262144),
    CONSTRAINT document_versions_fragments CHECK (fragment_count BETWEEN 1 AND 256),
    CONSTRAINT document_versions_number_positive CHECK (version_number > 0)
);

CREATE INDEX document_versions_project_idx ON agent_document.document_versions (project_id, created_at DESC, id DESC);

CREATE TABLE agent_document.document_fragments (
    version_id UUID NOT NULL REFERENCES agent_document.document_versions(id) ON DELETE RESTRICT,
    fragment_index INTEGER NOT NULL,
    start_offset INTEGER NOT NULL,
    content_text TEXT NOT NULL,
    fragment_sha256 CHAR(64) NOT NULL,
    PRIMARY KEY (version_id, fragment_index),
    CONSTRAINT document_fragments_index CHECK (fragment_index >= 0 AND fragment_index < 256),
    CONSTRAINT document_fragments_offset CHECK (start_offset >= 0),
    CONSTRAINT document_fragments_length CHECK (char_length(content_text) BETWEEN 1 AND 1024),
    CONSTRAINT document_fragments_sha CHECK (fragment_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE FUNCTION agent_document.reject_version_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'document versions and fragments are immutable';
END;
$$;

CREATE TRIGGER document_versions_immutable BEFORE UPDATE OR DELETE
    ON agent_document.document_versions FOR EACH ROW EXECUTE FUNCTION agent_document.reject_version_mutation();
CREATE TRIGGER document_fragments_immutable BEFORE UPDATE OR DELETE
    ON agent_document.document_fragments FOR EACH ROW EXECUTE FUNCTION agent_document.reject_version_mutation();
