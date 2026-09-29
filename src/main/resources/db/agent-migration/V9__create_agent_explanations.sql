CREATE SCHEMA agent;

CREATE TABLE agent.requests (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project.projects(id),
    requester_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    input_digest char(64) NOT NULL,
    trace_id varchar(128) NOT NULL,
    purpose varchar(32) NOT NULL CHECK (purpose = 'FINDING_EXPLANATION'),
    state varchar(16) NOT NULL CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    bindings jsonb NOT NULL,
    model_input jsonb,
    candidates jsonb,
    result jsonb,
    failure jsonb,
    usage jsonb,
    budget_day date,
    reserved_microusd bigint CHECK (reserved_microusd >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (project_id, requester_id, idempotency_key),
    CHECK ((state = 'SUCCEEDED' AND result IS NOT NULL AND failure IS NULL)
        OR (state = 'FAILED' AND result IS NULL AND failure IS NOT NULL)
        OR (state IN ('QUEUED','RUNNING') AND result IS NULL AND failure IS NULL))
);
CREATE INDEX agent_requests_project_idx ON agent.requests(project_id, created_at DESC);

CREATE TABLE agent.budget_usage (
    scope varchar(12) NOT NULL CHECK (scope IN ('PROJECT','DEPLOYMENT')),
    scope_id uuid NOT NULL,
    utc_day date NOT NULL,
    reserved_microusd bigint NOT NULL DEFAULT 0 CHECK (reserved_microusd >= 0),
    spent_microusd bigint NOT NULL DEFAULT 0 CHECK (spent_microusd >= 0),
    PRIMARY KEY (scope, scope_id, utc_day)
);

CREATE OR REPLACE FUNCTION agent.reject_terminal_request_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('SUCCEEDED','FAILED') THEN
        RAISE EXCEPTION 'Terminal Agent requests are immutable';
    END IF;
    IF OLD.state = 'RUNNING' AND NEW.state NOT IN ('RUNNING','SUCCEEDED','FAILED') THEN
        RAISE EXCEPTION 'Invalid Agent state transition';
    END IF;
    IF OLD.state = 'QUEUED' AND NEW.state NOT IN ('RUNNING','FAILED') THEN
        RAISE EXCEPTION 'Invalid Agent state transition';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.project_id IS DISTINCT FROM OLD.project_id
        OR NEW.requester_id IS DISTINCT FROM OLD.requester_id OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
        OR NEW.input_digest IS DISTINCT FROM OLD.input_digest OR NEW.bindings IS DISTINCT FROM OLD.bindings
        OR NEW.model_input IS DISTINCT FROM OLD.model_input OR NEW.candidates IS DISTINCT FROM OLD.candidates THEN
        RAISE EXCEPTION 'Agent request bindings are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER agent_request_transition BEFORE UPDATE ON agent.requests
    FOR EACH ROW EXECUTE FUNCTION agent.reject_terminal_request_change();

CREATE OR REPLACE FUNCTION agent.reject_request_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Agent request history cannot be deleted';
END;
$$;
CREATE TRIGGER agent_request_no_delete BEFORE DELETE ON agent.requests
    FOR EACH ROW EXECUTE FUNCTION agent.reject_request_delete();
