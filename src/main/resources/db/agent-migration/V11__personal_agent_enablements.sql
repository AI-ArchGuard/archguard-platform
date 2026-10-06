CREATE TABLE agent.personal_enablements (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project.projects(id),
    deployment_id uuid NOT NULL,
    approved_by uuid NOT NULL,
    approved_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > approved_at),
    credential_version uuid NOT NULL,
    acknowledgement jsonb NOT NULL CHECK (jsonb_typeof(acknowledgement) = 'object'),
    UNIQUE (project_id, id)
);
CREATE TABLE agent.enablement_revocations (
    enablement_id uuid PRIMARY KEY REFERENCES agent.personal_enablements(id),
    revoked_by uuid NOT NULL,
    revoked_at timestamptz NOT NULL
);
CREATE TABLE agent.project_settings (
    project_id uuid PRIMARY KEY REFERENCES project.projects(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    enablement_id uuid,
    FOREIGN KEY (project_id, enablement_id) REFERENCES agent.personal_enablements(project_id, id),
    CHECK ((enabled AND enablement_id IS NOT NULL) OR (NOT enabled AND enablement_id IS NULL))
);
CREATE INDEX agent_enablements_project_idx ON agent.personal_enablements(project_id, approved_at DESC);

CREATE FUNCTION agent.reject_enablement_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Agent enablement and revocation history is immutable';
END;
$$;
CREATE TRIGGER agent_enablement_immutable BEFORE UPDATE OR DELETE ON agent.personal_enablements
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
CREATE TRIGGER agent_revocation_immutable BEFORE UPDATE OR DELETE ON agent.enablement_revocations
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
