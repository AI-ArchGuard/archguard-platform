ALTER TABLE agent.requests ADD CONSTRAINT agent_requests_project_id_unique UNIQUE(project_id,id);

CREATE TABLE agent.live_batches (
    id uuid PRIMARY KEY, project_id uuid NOT NULL, enablement_id uuid NOT NULL,
    deployment_id uuid NOT NULL, inventory_id uuid NOT NULL, approved_by uuid NOT NULL,
    approved_at timestamptz NOT NULL, expires_at timestamptz NOT NULL CHECK(expires_at>approved_at),
    manifest_sha256 char(64) NOT NULL CHECK(manifest_sha256 ~ '^[0-9a-f]{64}$'),
    manifest jsonb NOT NULL CHECK(jsonb_typeof(manifest)='object'),
    max_requests integer NOT NULL CHECK(max_requests BETWEEN 1 AND 20),
    max_cost_microusd bigint NOT NULL CHECK(max_cost_microusd BETWEEN 4200 AND 84000 AND max_cost_microusd<=max_requests*4200),
    FOREIGN KEY(project_id,enablement_id) REFERENCES agent.personal_enablements(project_id,id),
    UNIQUE(project_id,id)
);
CREATE TABLE agent.live_batch_usage (
    batch_id uuid PRIMARY KEY REFERENCES agent.live_batches(id),
    reserved_microusd bigint NOT NULL DEFAULT 0 CHECK(reserved_microusd>=0),
    spent_microusd bigint NOT NULL DEFAULT 0 CHECK(spent_microusd>=0),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0)
);
CREATE TABLE agent.live_batch_revocations (
    batch_id uuid PRIMARY KEY REFERENCES agent.live_batches(id), revoked_by uuid NOT NULL, revoked_at timestamptz NOT NULL
);
CREATE TABLE agent.live_budget_usage (
    scope varchar(12) NOT NULL CHECK(scope IN ('DEPLOYMENT','PROJECT')), scope_id uuid NOT NULL, utc_day date NOT NULL,
    reserved_microusd bigint NOT NULL DEFAULT 0 CHECK(reserved_microusd>=0),
    spent_microusd bigint NOT NULL DEFAULT 0 CHECK(spent_microusd>=0), PRIMARY KEY(scope,scope_id,utc_day)
);
CREATE TABLE agent.live_attempts (
    request_id uuid PRIMARY KEY, project_id uuid NOT NULL, batch_id uuid NOT NULL,
    template_id uuid NOT NULL, attempt_id uuid NOT NULL UNIQUE, deployment_id uuid NOT NULL,
    enablement_id uuid NOT NULL, credential_version uuid NOT NULL, settings_revision bigint NOT NULL CHECK(settings_revision>=0),
    utc_day date NOT NULL, price_version varchar(100) NOT NULL,
    reserved_microusd bigint NOT NULL CHECK(reserved_microusd=4200), created_at timestamptz NOT NULL,
    FOREIGN KEY(project_id,request_id) REFERENCES agent.requests(project_id,id),
    FOREIGN KEY(project_id,template_id) REFERENCES agent.requests(project_id,id),
    FOREIGN KEY(project_id,batch_id) REFERENCES agent.live_batches(project_id,id),
    FOREIGN KEY(project_id,enablement_id) REFERENCES agent.personal_enablements(project_id,id)
);
CREATE TABLE agent.live_outcomes (
    request_id uuid PRIMARY KEY REFERENCES agent.live_attempts(request_id),
    state varchar(10) NOT NULL CHECK(state IN ('SETTLED','UNKNOWN')), usage jsonb, actual_microusd bigint,
    created_at timestamptz NOT NULL,
    CHECK((state='UNKNOWN' AND usage IS NULL AND actual_microusd IS NULL)
        OR (state='SETTLED' AND usage IS NOT NULL AND jsonb_typeof(usage)='object'
            AND actual_microusd IS NOT NULL AND actual_microusd BETWEEN 0 AND 4200))
);
CREATE TRIGGER live_batch_immutable BEFORE UPDATE OR DELETE ON agent.live_batches
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
CREATE TRIGGER live_batch_revocation_immutable BEFORE UPDATE OR DELETE ON agent.live_batch_revocations
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
CREATE TRIGGER live_attempt_immutable BEFORE UPDATE OR DELETE ON agent.live_attempts
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
CREATE TRIGGER live_outcome_immutable BEFORE UPDATE OR DELETE ON agent.live_outcomes
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
