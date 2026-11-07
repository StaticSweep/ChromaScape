-- Reconcile the disposable current schema with canonical legacy V63/V64/V71.
-- Released V1-V6 remain immutable. Historical rows keep nullable, unbound evidence;
-- adding storage never invents restore context, transition lineage or eligibility.
ALTER TABLE replay_checkpoints
    ADD COLUMN restore_context TEXT,
    ADD COLUMN chain_artifact_checksum VARCHAR(71);

ALTER TABLE state_hash_samples ADD COLUMN transition_ordinal BIGINT;
ALTER TABLE state_hash_samples ADD CONSTRAINT state_hash_samples_ordinal_check
    CHECK (transition_ordinal IS NULL OR transition_ordinal >= 0);

ALTER TABLE artifact_refs DROP CONSTRAINT artifact_refs_kind_check;
ALTER TABLE artifact_refs ADD CONSTRAINT artifact_refs_kind_check
    CHECK (kind IN ('MANIFEST', 'SNAPSHOT', 'TRACE', 'REPORT', 'CHAIN', 'PROJECTION', 'OUTCOME'));

-- Intentional new disposable contract. Existing nonempty schemas are still
-- rejected by the strict startup guard, never silently upgraded or reset.
ALTER TABLE ai_player_schema_identity DISABLE TRIGGER ai_player_identity_immutable;
ALTER TABLE ai_player_schema_identity DROP CONSTRAINT ai_player_schema_identity_identity_check;
UPDATE ai_player_schema_identity SET identity = 'sc276-current-v1';
ALTER TABLE ai_player_schema_identity ADD CONSTRAINT ai_player_schema_identity_identity_check
    CHECK (identity = 'sc276-current-v1');
ALTER TABLE ai_player_schema_identity ENABLE TRIGGER ai_player_identity_immutable;
