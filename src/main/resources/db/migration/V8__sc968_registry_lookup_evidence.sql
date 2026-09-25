ALTER TABLE patch_candidate_runs
    ADD COLUMN IF NOT EXISTS registry_lookup_bytes BYTEA;

ALTER TABLE patch_candidate_runs
    ADD COLUMN IF NOT EXISTS registry_lookup_hash VARCHAR(71);
