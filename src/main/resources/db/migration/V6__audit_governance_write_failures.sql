ALTER TABLE audit.audit_records DROP CONSTRAINT audit_records_result;
ALTER TABLE audit.audit_records ADD CONSTRAINT audit_records_result
    CHECK (result IN ('SUCCESS', 'DENIED', 'CONFLICT', 'FAILURE'));
