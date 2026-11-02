-- 日常请求只追加精确关联；不回填旧请求、不改变共享金额账本。
CREATE TABLE agent.daily_request_links (
    request_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    grant_id uuid NOT NULL,
    risk_acknowledgement_id uuid NOT NULL,
    credential_version uuid NOT NULL,
    settings_revision bigint NOT NULL CHECK(settings_revision>=0),
    runtime_binding_digest char(64) NOT NULL CHECK(runtime_binding_digest ~ '^[0-9a-f]{64}$'),
    payload_sha256 char(64) NOT NULL CHECK(payload_sha256 ~ '^[0-9a-f]{64}$'),
    candidates_sha256 char(64) NOT NULL CHECK(candidates_sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    FOREIGN KEY(project_id,request_id) REFERENCES agent.requests(project_id,id),
    FOREIGN KEY(project_id,grant_id) REFERENCES agent.daily_grants(project_id,id),
    FOREIGN KEY(project_id,risk_acknowledgement_id) REFERENCES agent.daily_risk_acknowledgements(project_id,id),
    UNIQUE(project_id,grant_id,request_id)
);
CREATE INDEX daily_request_links_project_order ON agent.daily_request_links(project_id,created_at DESC,request_id DESC);

-- 两侧先取得同一请求锁，之后才检查另一侧；各自旧触发器保持原样。
CREATE FUNCTION agent.exclude_live_from_daily_link() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM agent.requests WHERE project_id=NEW.project_id AND id=NEW.request_id FOR UPDATE;
    IF EXISTS(SELECT 1 FROM agent.live_request_links WHERE project_id=NEW.project_id AND request_id=NEW.request_id) THEN
        RAISE EXCEPTION 'Request mode conflict' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION agent.exclude_daily_from_live_link() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM agent.requests WHERE project_id=NEW.project_id AND id=NEW.request_id FOR UPDATE;
    IF EXISTS(SELECT 1 FROM agent.daily_request_links WHERE project_id=NEW.project_id AND request_id=NEW.request_id) THEN
        RAISE EXCEPTION 'Request mode conflict' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER a_daily_request_mode BEFORE INSERT ON agent.daily_request_links
    FOR EACH ROW EXECUTE FUNCTION agent.exclude_live_from_daily_link();
CREATE TRIGGER a_live_request_mode BEFORE INSERT ON agent.live_request_links
    FOR EACH ROW EXECUTE FUNCTION agent.exclude_daily_from_live_link();

CREATE FUNCTION agent.check_daily_request_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    g agent.daily_grants%ROWTYPE;
    risk agent.daily_risk_acknowledgements%ROWTYPE;
    r agent.requests%ROWTYPE;
    settings agent.daily_project_settings%ROWTYPE;
    binding jsonb;
BEGIN
    -- 引用缺失或跨 Project 交由精确外键判定，不能改成绑定错误。
    SELECT * INTO g FROM agent.daily_grants WHERE project_id=NEW.project_id AND id=NEW.grant_id;
    IF NOT FOUND THEN RETURN NEW; END IF;
    SELECT * INTO risk FROM agent.daily_risk_acknowledgements WHERE project_id=NEW.project_id AND id=NEW.risk_acknowledgement_id;
    IF NOT FOUND THEN RETURN NEW; END IF;
    SELECT * INTO r FROM agent.requests WHERE project_id=NEW.project_id AND id=NEW.request_id;
    IF NOT FOUND THEN RETURN NEW; END IF;
    SELECT * INTO settings FROM agent.daily_project_settings WHERE project_id=NEW.project_id;
    binding := risk.acknowledgement->'runtimeBinding';
    IF g.risk_acknowledgement_id IS DISTINCT FROM risk.id
        OR g.owner_id IS DISTINCT FROM r.requester_id OR risk.owner_id IS DISTINCT FROM r.requester_id
        OR g.credential_version IS DISTINCT FROM NEW.credential_version OR risk.credential_version IS DISTINCT FROM NEW.credential_version
        OR g.runtime_binding_digest IS DISTINCT FROM NEW.runtime_binding_digest OR risk.runtime_binding_digest IS DISTINCT FROM NEW.runtime_binding_digest
        OR settings.enabled IS DISTINCT FROM true OR settings.grant_id IS DISTINCT FROM g.id OR settings.revision IS DISTINCT FROM NEW.settings_revision
        OR NEW.created_at IS DISTINCT FROM r.created_at
        OR NOT (r.purpose=ANY(g.allowed_purposes))
        OR r.input_digest::text IS DISTINCT FROM r.bindings->>'inputDigest'
        OR r.bindings->>'modelProfileVersion' IS DISTINCT FROM 'deepseek-personal-0.2.0'
        OR r.bindings->>'modelProtocolVersion' IS DISTINCT FROM 'responses-v1-restricted'
        OR r.bindings->>'modelId' IS DISTINCT FROM 'deepseek-flash'
        OR r.bindings->>'outputSchemaVersion' IS DISTINCT FROM '0.1.0'
        OR r.bindings->>'priceCatalogVersion' IS DISTINCT FROM 'deepseek-flash-context-peak-usd-2026-10-15'
        OR r.bindings->>'promptVersion' IS DISTINCT FROM CASE r.purpose WHEN 'PR_SUMMARY' THEN 'pr-summary-deepseek-0.1.0' ELSE 'finding-explanation-deepseek-0.1.0' END
        OR r.bindings->>'modelProtocolVersion' IS DISTINCT FROM binding->>'modelProtocolVersion'
        OR r.bindings->>'modelId' IS DISTINCT FROM binding->>'modelAlias'
        OR r.bindings->>'outputSchemaVersion' IS DISTINCT FROM binding->>'outputSchemaVersion'
        OR r.bindings->>'priceCatalogVersion' IS DISTINCT FROM binding->>'priceCatalogVersion'
        OR r.bindings->>'promptVersion' IS DISTINCT FROM CASE r.purpose WHEN 'PR_SUMMARY' THEN binding->>'summaryPromptVersion' ELSE binding->>'explanationPromptVersion' END
        OR r.model_input->>'purpose' IS DISTINCT FROM r.purpose
        OR r.model_input->>'schemaVersion' IS DISTINCT FROM r.bindings->>'outputSchemaVersion'
        OR r.model_input->>'promptVersion' IS DISTINCT FROM r.bindings->>'promptVersion' THEN
        RAISE EXCEPTION 'Daily request binding does not match' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER daily_request_binding BEFORE INSERT ON agent.daily_request_links
    FOR EACH ROW EXECUTE FUNCTION agent.check_daily_request_binding();
CREATE TRIGGER daily_request_immutable BEFORE UPDATE OR DELETE ON agent.daily_request_links
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();

CREATE TABLE agent.daily_attempts (
    request_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    grant_id uuid NOT NULL,
    attempt_id uuid NOT NULL UNIQUE,
    deployment_id uuid NOT NULL,
    credential_version uuid NOT NULL,
    settings_revision bigint NOT NULL CHECK(settings_revision>=0),
    runtime_binding_digest char(64) NOT NULL CHECK(runtime_binding_digest ~ '^[0-9a-f]{64}$'),
    utc_day date NOT NULL,
    price_version text NOT NULL CHECK(price_version='deepseek-flash-context-peak-usd-2026-10-15'),
    reserved_microusd bigint NOT NULL CHECK(reserved_microusd=316373),
    created_at timestamptz NOT NULL,
    FOREIGN KEY(project_id,grant_id,request_id) REFERENCES agent.daily_request_links(project_id,grant_id,request_id)
);
CREATE FUNCTION agent.check_daily_attempt_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE link agent.daily_request_links%ROWTYPE; deployment uuid; price text;
BEGIN
    SELECT * INTO link FROM agent.daily_request_links WHERE project_id=NEW.project_id AND grant_id=NEW.grant_id AND request_id=NEW.request_id;
    IF NOT FOUND THEN RETURN NEW; END IF;
    SELECT deployment_id INTO deployment FROM agent.daily_grants WHERE project_id=NEW.project_id AND id=NEW.grant_id;
    SELECT bindings->>'priceCatalogVersion' INTO price FROM agent.requests WHERE project_id=NEW.project_id AND id=NEW.request_id;
    IF NEW.deployment_id IS DISTINCT FROM deployment OR NEW.credential_version IS DISTINCT FROM link.credential_version
        OR NEW.settings_revision IS DISTINCT FROM link.settings_revision OR NEW.runtime_binding_digest IS DISTINCT FROM link.runtime_binding_digest
        OR NEW.price_version IS DISTINCT FROM price THEN
        RAISE EXCEPTION 'Daily attempt binding does not match' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER daily_attempt_binding BEFORE INSERT ON agent.daily_attempts
    FOR EACH ROW EXECUTE FUNCTION agent.check_daily_attempt_binding();
CREATE TRIGGER daily_attempt_immutable BEFORE UPDATE OR DELETE ON agent.daily_attempts
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();

CREATE TABLE agent.daily_outcomes (
    request_id uuid PRIMARY KEY REFERENCES agent.daily_attempts(request_id),
    state varchar(10) NOT NULL CHECK(state IN ('SETTLED','UNKNOWN')),
    usage jsonb,
    actual_microusd bigint,
    created_at timestamptz NOT NULL,
    CHECK((state='UNKNOWN' AND usage IS NULL AND actual_microusd IS NULL)
        OR (state='SETTLED' AND usage IS NOT NULL AND jsonb_typeof(usage)='object' AND actual_microusd IS NOT NULL AND actual_microusd>=0))
);
CREATE FUNCTION agent.check_daily_outcome_amount() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE reservation bigint;
BEGIN
    SELECT reserved_microusd INTO reservation FROM agent.daily_attempts WHERE request_id=NEW.request_id;
    IF FOUND AND NEW.state='SETTLED' AND NEW.actual_microusd>reservation THEN
        RAISE EXCEPTION 'Daily outcome exceeds reservation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER daily_outcome_amount BEFORE INSERT ON agent.daily_outcomes
    FOR EACH ROW EXECUTE FUNCTION agent.check_daily_outcome_amount();
CREATE TRIGGER daily_outcome_immutable BEFORE UPDATE OR DELETE ON agent.daily_outcomes
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();

CREATE TABLE agent.daily_grant_stops (
    grant_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    request_id uuid NOT NULL,
    code varchar(40) NOT NULL CHECK(code IN ('MODEL_DISABLED','MODEL_UNAVAILABLE','MODEL_TIMEOUT',
        'AUTHORIZATION_REVOKED','QUOTA_EXHAUSTED','OUTPUT_INVALID','CITATION_INVALID','INTERNAL_ERROR','INPUT_TOKEN_LIMIT')),
    created_at timestamptz NOT NULL,
    FOREIGN KEY(project_id,grant_id,request_id) REFERENCES agent.daily_request_links(project_id,grant_id,request_id)
);
CREATE TRIGGER daily_grant_stop_immutable BEFORE UPDATE OR DELETE ON agent.daily_grant_stops
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
ALTER TABLE agent.daily_grant_usage ADD CONSTRAINT daily_active_request_exact
    FOREIGN KEY(project_id,grant_id,active_request_id) REFERENCES agent.daily_request_links(project_id,grant_id,request_id);
