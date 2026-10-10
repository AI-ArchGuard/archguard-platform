-- Preserve V12 history. Legacy batches remain readable but lack provenance for new attempts.
ALTER TABLE agent.live_batches ADD COLUMN inventory_proof jsonb;
ALTER TABLE agent.live_batches ADD CONSTRAINT live_batch_inventory_proof_object
    CHECK (inventory_proof IS NULL OR jsonb_typeof(inventory_proof) = 'object');
