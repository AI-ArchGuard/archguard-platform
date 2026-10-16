-- A committed context-policy link consumes its template slot even before an attempt exists.
-- Older policies and their links retain their original semantics.
ALTER TABLE agent.live_request_links ADD CONSTRAINT live_request_links_exact_slot_unique
    UNIQUE(project_id,batch_id,template_id,request_id);

CREATE TABLE agent.live_template_request_slots (
    project_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    template_id uuid NOT NULL,
    request_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY(project_id,batch_id,template_id),
    FOREIGN KEY(project_id,batch_id,template_id,request_id)
        REFERENCES agent.live_request_links(project_id,batch_id,template_id,request_id)
);

CREATE FUNCTION agent.check_context_template_request_slot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE batch_policy varchar(100);
BEGIN
    SELECT cost_policy_version INTO batch_policy FROM agent.live_batches
        WHERE project_id=NEW.project_id AND id=NEW.batch_id;
    -- Missing references are rejected by the exact link foreign key, not reclassified as policy errors.
    IF batch_policy IS NOT NULL AND batch_policy<>'deepseek-flash-context-peak-usd-2026-10-15' THEN
        RAISE EXCEPTION 'Agent template request slots require the context cost policy' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER live_template_request_slot_policy BEFORE INSERT ON agent.live_template_request_slots
    FOR EACH ROW EXECUTE FUNCTION agent.check_context_template_request_slot();
CREATE TRIGGER live_template_request_slot_immutable BEFORE UPDATE OR DELETE ON agent.live_template_request_slots
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();

-- No ON CONFLICT or winner selection: duplicate historical context slots must abort this migration.
-- PostgreSQL transactional DDL preserves all original history if that occurs.
INSERT INTO agent.live_template_request_slots(project_id,batch_id,template_id,request_id,created_at)
    SELECT link.project_id,link.batch_id,link.template_id,link.request_id,link.created_at
    FROM agent.live_request_links link
    JOIN agent.live_batches batch ON batch.project_id=link.project_id AND batch.id=link.batch_id
    WHERE batch.cost_policy_version='deepseek-flash-context-peak-usd-2026-10-15';

CREATE FUNCTION agent.claim_context_template_request_slot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM agent.live_batches WHERE project_id=NEW.project_id AND id=NEW.batch_id
            AND cost_policy_version='deepseek-flash-context-peak-usd-2026-10-15') THEN
        INSERT INTO agent.live_template_request_slots(project_id,batch_id,template_id,request_id,created_at)
            VALUES(NEW.project_id,NEW.batch_id,NEW.template_id,NEW.request_id,NEW.created_at);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER live_request_link_template_slot AFTER INSERT ON agent.live_request_links
    FOR EACH ROW EXECUTE FUNCTION agent.claim_context_template_request_slot();
