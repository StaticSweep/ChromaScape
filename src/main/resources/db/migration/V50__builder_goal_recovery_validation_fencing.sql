-- SC-1173: package content is reusable across independent validation lineages. The candidate
-- record itself remains unique per validation job, which prevents double publication.
ALTER TABLE micro6_candidate_artifact
    DROP CONSTRAINT IF EXISTS uq_micro6_candidate_digest;

CREATE INDEX IF NOT EXISTS idx_micro6_candidate_digest
    ON micro6_candidate_artifact(candidate_digest);

-- Guarded so the constraint can already be present from the equivalent migration applied
-- under a different version number in an existing lineage.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_micro6_candidate_job_id'
          AND conrelid = 'micro6_candidate_artifact'::regclass
    ) THEN
        ALTER TABLE micro6_candidate_artifact
            ADD CONSTRAINT uq_micro6_candidate_job_id UNIQUE (job_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_bg_validation_lease
    ON micro6_patch_validation_job(state, lease_expires_at);
