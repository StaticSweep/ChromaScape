-- SC-1173: package content is reusable across independent validation lineages. The candidate
-- record itself remains unique per validation job, which prevents double publication.
ALTER TABLE micro6_candidate_artifact
    DROP CONSTRAINT IF EXISTS uq_micro6_candidate_digest;

CREATE INDEX IF NOT EXISTS idx_micro6_candidate_digest
    ON micro6_candidate_artifact(candidate_digest);

ALTER TABLE micro6_candidate_artifact
    ADD CONSTRAINT uq_micro6_candidate_job_id UNIQUE (job_id);

CREATE INDEX IF NOT EXISTS idx_bg_validation_lease
    ON micro6_patch_validation_job(state, lease_expires_at);
