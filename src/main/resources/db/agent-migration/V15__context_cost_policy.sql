-- Append a distinct policy without reinterpreting existing approvals, reservations or outcomes.
ALTER TABLE agent.live_batches ADD COLUMN cost_policy_version varchar(100) NOT NULL
    DEFAULT 'deepseek-flash-peak-usd-2026-10-06';
ALTER TABLE agent.live_batches DROP CONSTRAINT live_batches_check1;
ALTER TABLE agent.live_batches ADD CONSTRAINT live_batches_versioned_cost_check CHECK (
    (cost_policy_version='deepseek-flash-peak-usd-2026-10-06'
        AND max_cost_microusd BETWEEN 4200 AND 84000 AND max_cost_microusd<=max_requests*4200)
    OR (cost_policy_version='deepseek-flash-context-peak-usd-2026-10-15'
        AND max_requests=2 AND max_cost_microusd BETWEEN 632746 AND 700000)
);

ALTER TABLE agent.live_attempts DROP CONSTRAINT live_attempts_reserved_microusd_check;
ALTER TABLE agent.live_attempts ADD CONSTRAINT live_attempts_versioned_reservation_check CHECK (
    (price_version='deepseek-flash-context-peak-usd-2026-10-15' AND reserved_microusd=316373)
    OR (price_version<>'deepseek-flash-context-peak-usd-2026-10-15' AND reserved_microusd=4200)
);

CREATE FUNCTION agent.check_live_attempt_cost_policy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE batch_policy varchar(100);
BEGIN
    SELECT cost_policy_version INTO batch_policy FROM agent.live_batches
        WHERE project_id=NEW.project_id AND id=NEW.batch_id;
    IF NEW.price_version='deepseek-flash-context-peak-usd-2026-10-15'
            OR batch_policy='deepseek-flash-context-peak-usd-2026-10-15' THEN
        IF batch_policy IS DISTINCT FROM NEW.price_version THEN
            RAISE EXCEPTION 'Agent attempt cost policy does not match its batch' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER live_attempt_cost_policy BEFORE INSERT ON agent.live_attempts
    FOR EACH ROW EXECUTE FUNCTION agent.check_live_attempt_cost_policy();

ALTER TABLE agent.live_outcomes DROP CONSTRAINT live_outcomes_check;
ALTER TABLE agent.live_outcomes ADD CONSTRAINT live_outcomes_shape_check CHECK (
    (state='UNKNOWN' AND usage IS NULL AND actual_microusd IS NULL)
    OR (state='SETTLED' AND usage IS NOT NULL AND jsonb_typeof(usage)='object'
        AND actual_microusd IS NOT NULL AND actual_microusd>=0)
);
CREATE FUNCTION agent.check_live_outcome_reservation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE reservation bigint;
BEGIN
    SELECT reserved_microusd INTO reservation FROM agent.live_attempts WHERE request_id=NEW.request_id;
    IF NEW.state='SETTLED' AND NEW.actual_microusd>reservation THEN
        RAISE EXCEPTION 'Agent known charge exceeds its bound reservation' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER live_outcome_reservation BEFORE INSERT ON agent.live_outcomes
    FOR EACH ROW EXECUTE FUNCTION agent.check_live_outcome_reservation();

ALTER TABLE agent.live_request_links ADD CONSTRAINT live_request_links_project_batch_request_unique
    UNIQUE(project_id,batch_id,request_id);
CREATE TABLE agent.live_batch_stops (
    batch_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    request_id uuid NOT NULL,
    code varchar(40) NOT NULL CHECK(code IN ('MODEL_DISABLED','MODEL_UNAVAILABLE','MODEL_TIMEOUT',
        'AUTHORIZATION_REVOKED','QUOTA_EXHAUSTED','OUTPUT_INVALID','CITATION_INVALID','INTERNAL_ERROR','INPUT_TOKEN_LIMIT')),
    created_at timestamptz NOT NULL,
    FOREIGN KEY(project_id,batch_id) REFERENCES agent.live_batches(project_id,id),
    FOREIGN KEY(project_id,batch_id,request_id) REFERENCES agent.live_request_links(project_id,batch_id,request_id)
);
CREATE TRIGGER live_batch_stop_immutable BEFORE UPDATE OR DELETE ON agent.live_batch_stops
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
