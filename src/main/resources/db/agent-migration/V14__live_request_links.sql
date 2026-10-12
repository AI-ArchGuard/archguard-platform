-- Preserve legacy requests and attempts without inventing approval associations.
CREATE TABLE agent.live_request_links (
    request_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    template_id uuid NOT NULL,
    enablement_id uuid NOT NULL,
    credential_version uuid NOT NULL,
    settings_revision bigint NOT NULL CHECK (settings_revision >= 0),
    created_at timestamptz NOT NULL,
    CHECK (request_id <> template_id),
    FOREIGN KEY (project_id,request_id) REFERENCES agent.requests(project_id,id),
    FOREIGN KEY (project_id,template_id) REFERENCES agent.requests(project_id,id),
    FOREIGN KEY (project_id,batch_id) REFERENCES agent.live_batches(project_id,id),
    FOREIGN KEY (project_id,enablement_id) REFERENCES agent.personal_enablements(project_id,id)
);
CREATE INDEX live_request_links_project_idx ON agent.live_request_links(project_id,created_at DESC);
CREATE TRIGGER live_request_link_immutable BEFORE UPDATE OR DELETE ON agent.live_request_links
    FOR EACH ROW EXECUTE FUNCTION agent.reject_enablement_history_change();
