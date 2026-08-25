-- V15: Align replay schema with JPA mappings and add branch semantics.
-- V11-V14 remain immutable per Flyway policy; this migration repairs gaps:
--   * replay_mismatches.attempt_id (entity requires it, V14 omitted it)
--   * branch lineage columns on replay_runs (branch_key / parent_run_id /
--     parent_checkpoint_id) required for real branch-from-checkpoint runs.

ALTER TABLE replay_mismatches
    ADD COLUMN IF NOT EXISTS attempt_id UUID NOT NULL DEFAULT gen_random_uuid();

-- Backfill attempt_id from the run's latest attempt where possible.
UPDATE replay_mismatches m
SET attempt_id = a.id
FROM LATERAL (
    SELECT id FROM replay_attempts
    WHERE run_id = m.run_id
    ORDER BY attempt_no DESC
    LIMIT 1
) a
WHERE m.attempt_id IS NULL;

ALTER TABLE replay_mismatches
    ALTER COLUMN attempt_id DROP DEFAULT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_mismatch_attempt'
    ) THEN
        ALTER TABLE replay_mismatches
            ADD CONSTRAINT fk_mismatch_attempt
            FOREIGN KEY (attempt_id) REFERENCES replay_attempts(id) ON DELETE CASCADE;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_replay_mismatches_attempt ON replay_mismatches(attempt_id);

-- Branch lineage for runs created from a checkpoint.
ALTER TABLE replay_runs ADD COLUMN IF NOT EXISTS branch_key VARCHAR NOT NULL DEFAULT 'main';
ALTER TABLE replay_runs ADD COLUMN IF NOT EXISTS parent_run_id UUID REFERENCES replay_runs(id) ON DELETE SET NULL;
ALTER TABLE replay_runs ADD COLUMN IF NOT EXISTS parent_checkpoint_id UUID;

CREATE INDEX IF NOT EXISTS idx_replay_runs_parent ON replay_runs(parent_run_id);
