ALTER TABLE agent.requests DROP CONSTRAINT requests_purpose_check;
ALTER TABLE agent.requests ADD CONSTRAINT ck_agent_requests_purpose
    CHECK (purpose IN ('FINDING_EXPLANATION','PR_SUMMARY'));
