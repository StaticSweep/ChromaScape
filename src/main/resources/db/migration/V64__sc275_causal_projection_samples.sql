-- New causal evidence is additive; historical v1 rows retain null ordinals.
ALTER TABLE state_hash_samples ADD COLUMN IF NOT EXISTS transition_ordinal BIGINT;
ALTER TABLE state_hash_samples ADD CONSTRAINT state_hash_samples_ordinal_check
    CHECK (transition_ordinal IS NULL OR transition_ordinal >= 0);
ALTER TABLE artifact_refs DROP CONSTRAINT IF EXISTS artifact_refs_kind_check;
ALTER TABLE artifact_refs ADD CONSTRAINT artifact_refs_kind_check
    CHECK (kind IN ('MANIFEST', 'SNAPSHOT', 'TRACE', 'REPORT', 'CHAIN', 'PROJECTION'));
