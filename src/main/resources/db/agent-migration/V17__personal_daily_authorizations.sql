-- 日常风险、费用许可与旧合成批准完全独立；不回填历史，也不创建请求或授予默认额度。
CREATE TABLE agent.daily_risk_acknowledgements (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project.projects(id),
    deployment_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK (idempotency_key COLLATE "C" ~ '^[!-~]{1,128}$'),
    input_digest char(64) NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    runtime_binding_digest char(64) NOT NULL CHECK (runtime_binding_digest ~ '^[0-9a-f]{64}$'),
    credential_version uuid NOT NULL,
    data_policy_version text NOT NULL CHECK (data_policy_version='personal-selected-nonsensitive-0.1.0'),
    acknowledged_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at>acknowledged_at AND expires_at<=acknowledged_at+INTERVAL '7 days'),
    acknowledgement jsonb NOT NULL CHECK (jsonb_typeof(acknowledgement)='object'),
    UNIQUE(project_id,id),
    UNIQUE(project_id,owner_id,idempotency_key)
);

-- 仅核对内部封套与标量一致性；完整绑定的 SHA256 仍由唯一应用策略计算，数据库不伪称证明外部核对事实。
CREATE FUNCTION agent.check_daily_risk_envelope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF jsonb_typeof(NEW.acknowledgement) IS DISTINCT FROM 'object'
        OR NEW.acknowledgement - ARRAY['schemaVersion','runtimeBindingDigest','runtimeBinding','expiresAt','sourcesReviewed','riskAccepted','materialRightsAccepted'] <> '{}'::jsonb
        OR NEW.acknowledgement->>'schemaVersion' IS DISTINCT FROM '0.1.0'
        OR NEW.acknowledgement->>'runtimeBindingDigest' IS DISTINCT FROM NEW.runtime_binding_digest::text
        OR jsonb_typeof(NEW.acknowledgement->'runtimeBinding') IS DISTINCT FROM 'object'
        OR NEW.acknowledgement#>>'{runtimeBinding,ownerId}' IS DISTINCT FROM NEW.owner_id::text
        OR NEW.acknowledgement#>>'{runtimeBinding,deploymentId}' IS DISTINCT FROM NEW.deployment_id::text
        OR NEW.acknowledgement#>>'{runtimeBinding,credentialVersion}' IS DISTINCT FROM NEW.credential_version::text
        OR NEW.acknowledgement#>>'{runtimeBinding,dataPolicyVersion}' IS DISTINCT FROM NEW.data_policy_version
        OR NEW.acknowledgement->'sourcesReviewed' IS DISTINCT FROM 'true'::jsonb
        OR NEW.acknowledgement->'riskAccepted' IS DISTINCT FROM 'true'::jsonb
        OR NEW.acknowledgement->'materialRightsAccepted' IS DISTINCT FROM 'true'::jsonb
        OR (NEW.acknowledgement->>'expiresAt')::timestamptz IS DISTINCT FROM NEW.expires_at THEN
        RAISE EXCEPTION 'Daily risk binding does not match' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow THEN
    RAISE EXCEPTION 'Daily risk expiry is invalid' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER daily_risk_envelope BEFORE INSERT ON agent.daily_risk_acknowledgements
    FOR EACH ROW EXECUTE FUNCTION agent.check_daily_risk_envelope();

CREATE TABLE agent.daily_risk_revocations (
    risk_acknowledgement_id uuid PRIMARY KEY REFERENCES agent.daily_risk_acknowledgements(id),
    revoked_by uuid NOT NULL,
    revoked_at timestamptz NOT NULL
);

CREATE FUNCTION agent.valid_daily_purposes(values_to_check text[]) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT values_to_check IS NOT NULL AND array_ndims(values_to_check)=1 AND cardinality(values_to_check)>0
        AND NOT EXISTS(SELECT 1 FROM unnest(values_to_check) AS entries(purpose) WHERE purpose IS NULL OR purpose NOT IN ('FINDING_EXPLANATION','PR_SUMMARY'))
        AND cardinality(values_to_check)=(SELECT count(DISTINCT purpose) FROM unnest(values_to_check) AS entries(purpose));
$$;
CREATE TABLE agent.daily_grants (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    deployment_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    risk_acknowledgement_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK (idempotency_key COLLATE "C" ~ '^[!-~]{1,128}$'),
    input_digest char(64) NOT NULL CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    runtime_binding_digest char(64) NOT NULL CHECK (runtime_binding_digest ~ '^[0-9a-f]{64}$'),
    credential_version uuid NOT NULL,
    allowed_purposes text[] NOT NULL CHECK (agent.valid_daily_purposes(allowed_purposes)),
    max_attempts integer NOT NULL CHECK(max_attempts>=1),
    max_cost_microusd bigint NOT NULL CHECK(max_cost_microusd>=316373 AND max_cost_microusd<=max_attempts::bigint*350000),
    approved_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK(expires_at>approved_at),
    FOREIGN KEY(project_id,risk_acknowledgement_id) REFERENCES agent.daily_risk_acknowledgements(project_id,id),
    UNIQUE(project_id,id),
    UNIQUE(project_id,owner_id,idempotency_key)
);
CREATE FUNCTION agent.check_daily_grant_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE risk agent.daily_risk_acknowledgements%ROWTYPE;
BEGIN
    SELECT * INTO risk FROM agent.daily_risk_acknowledgements WHERE id=NEW.risk_acknowledgement_id;
    -- 不存在的引用交由复合外键统一拒绝；跨 Project 不能被改写成存在性泄漏。
    IF FOUND AND (NEW.owner_id IS DISTINCT FROM risk.owner_id
            OR NEW.deployment_id IS DISTINCT FROM risk.deployment_id
            OR NEW.runtime_binding_digest IS DISTINCT FROM risk.runtime_binding_digest
            OR NEW.credential_version IS DISTINCT FROM risk.credential_version
            OR NEW.expires_at>risk.expires_at) THEN
        RAISE EXCEPTION 'Daily grant binding does not match' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER daily_grant_binding BEFORE INSERT ON agent.daily_grants
    FOR EACH ROW EXECUTE FUNCTION agent.check_daily_grant_binding();

CREATE TABLE agent.daily_grant_revocations (
    grant_id uuid PRIMARY KEY REFERENCES agent.daily_grants(id),
    revoked_by uuid NOT NULL,
    revoked_at timestamptz NOT NULL
);
CREATE TABLE agent.daily_project_settings (
    project_id uuid PRIMARY KEY REFERENCES project.projects(id),
    enabled boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    grant_id uuid,
    FOREIGN KEY(project_id,grant_id) REFERENCES agent.daily_grants(project_id,id),
    CHECK((enabled AND grant_id IS NOT NULL) OR (NOT enabled AND grant_id IS NULL))
);
CREATE TABLE agent.daily_grant_usage (
    grant_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
    reserved_microusd bigint NOT NULL DEFAULT 0 CHECK(reserved_microusd>=0),
    spent_microusd bigint NOT NULL DEFAULT 0 CHECK(spent_microusd>=0),
    active_request_id uuid,
    FOREIGN KEY(project_id,grant_id) REFERENCES agent.daily_grants(project_id,id)
);
CREATE FUNCTION agent.check_daily_grant_usage() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE attempt_cap integer; cost_cap bigint;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF NEW.grant_id IS DISTINCT FROM OLD.grant_id OR NEW.project_id IS DISTINCT FROM OLD.project_id OR NEW.attempts<OLD.attempts THEN
            RAISE EXCEPTION 'Daily grant usage identity or count cannot change' USING ERRCODE='23514';
        END IF;
    END IF;
    SELECT max_attempts,max_cost_microusd INTO attempt_cap,cost_cap FROM agent.daily_grants WHERE id=NEW.grant_id;
    -- INSERT 同样受上限保护；numeric 求和避免 bigint 溢出绕过累计额度。
    IF FOUND AND (NEW.attempts>attempt_cap OR NEW.reserved_microusd::numeric+NEW.spent_microusd::numeric>cost_cap) THEN
        RAISE EXCEPTION 'Daily grant usage exceeds its limits' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER daily_grant_usage_caps BEFORE INSERT OR UPDATE ON agent.daily_grant_usage
    FOR EACH ROW EXECUTE FUNCTION agent.check_daily_grant_usage();

-- 批准与撤销只能追加；复用已发布的不可变历史函数，不重写旧函数或旧迁移。
CREATE TRIGGER daily_risk_immutable BEFORE UPDATE OR DELETE ON agent.daily_risk_acknowledgements
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
CREATE TRIGGER daily_risk_revocation_immutable BEFORE UPDATE OR DELETE ON agent.daily_risk_revocations
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
CREATE TRIGGER daily_grant_immutable BEFORE UPDATE OR DELETE ON agent.daily_grants
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
CREATE TRIGGER daily_grant_revocation_immutable BEFORE UPDATE OR DELETE ON agent.daily_grant_revocations
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
CREATE INDEX daily_risk_project_idx ON agent.daily_risk_acknowledgements(project_id,acknowledged_at DESC,id);
CREATE INDEX daily_grants_project_idx ON agent.daily_grants(project_id,approved_at DESC,id);
CREATE INDEX daily_grants_risk_idx ON agent.daily_grants(project_id,risk_acknowledgement_id,id);
