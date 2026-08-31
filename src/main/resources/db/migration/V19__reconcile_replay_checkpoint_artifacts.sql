-- V19: reconcile replay_checkpoints artifact-checksum columns across every known V18 state.
--
-- V18 renamed snapshot_artifact_id -> snapshot_artifact_checksum when only the old column existed,
-- but a database can legitimately be in one of FOUR states depending on when/how V18 ran:
--   (a) old-only   (V18 never applied its rename)
--   (b) new-only   (clean post-V18)
--   (c) both       (V18 ADD COLUMN IF NOT EXISTS ran after a manual/partial rename)
--   (d) neither    (pre-V13 legacy row shape; defensive)
-- V18 is already applied in some environments and must never be rewritten; this migration makes
-- every state converge to exactly one NOT NULL, format-valid checksum column.

DO $$
DECLARE
    has_old boolean;
    has_new boolean;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'replay_checkpoints'
          AND column_name = 'snapshot_artifact_id'
    ) INTO has_old;

    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'replay_checkpoints'
          AND column_name = 'snapshot_artifact_checksum'
    ) INTO has_new;

    IF has_old AND has_new THEN
        -- Both columns present: unequal non-null pairs are unrecoverable evidence conflicts.
        IF EXISTS (
            SELECT 1 FROM replay_checkpoints
            WHERE snapshot_artifact_id IS NOT NULL
              AND snapshot_artifact_checksum IS NOT NULL
              AND snapshot_artifact_id <> snapshot_artifact_checksum
        ) THEN
            RAISE EXCEPTION 'V19 reconciliation conflict: replay_checkpoints rows carry different values in snapshot_artifact_id and snapshot_artifact_checksum';
        END IF;
        -- Backfill the new column from the old value where the new one was never written.
        UPDATE replay_checkpoints
        SET snapshot_artifact_checksum = snapshot_artifact_id
        WHERE snapshot_artifact_checksum IS NULL
          AND snapshot_artifact_id IS NOT NULL;
        ALTER TABLE replay_checkpoints DROP COLUMN snapshot_artifact_id;
    ELSIF has_old AND NOT has_new THEN
        ALTER TABLE replay_checkpoints
            RENAME COLUMN snapshot_artifact_id TO snapshot_artifact_checksum;
    ELSIF NOT has_old AND NOT has_new THEN
        ALTER TABLE replay_checkpoints
            ADD COLUMN snapshot_artifact_checksum VARCHAR(71);
    END IF;

    -- Normalize the serialized-artifact content address type.
    ALTER TABLE replay_checkpoints
        ALTER COLUMN snapshot_artifact_checksum TYPE VARCHAR(71);
END $$;

-- Reject missing content addresses with an explicit migration error BEFORE the NOT NULL flip,
-- so the failure names the offending invariant instead of surfacing a bare not-null violation.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM replay_checkpoints
        WHERE snapshot_artifact_checksum IS NULL
           OR btrim(snapshot_artifact_checksum) = ''
    ) THEN
        RAISE EXCEPTION 'V19 integrity failure: replay_checkpoints.snapshot_artifact_checksum contains NULL or blank values; checkpoint artifact evidence is incomplete';
    END IF;
    IF EXISTS (
        SELECT 1 FROM replay_checkpoints
        WHERE snapshot_checksum IS NULL
           OR btrim(snapshot_checksum) = ''
    ) THEN
        RAISE EXCEPTION 'V19 integrity failure: replay_checkpoints.snapshot_checksum contains NULL or blank values; kernel snapshot evidence is incomplete';
    END IF;
END $$;

-- Strict lowercase SHA-256 content-address format for BOTH checksum fields.
-- Replaces V18's NULL-tolerant constraint.
ALTER TABLE replay_checkpoints
    DROP CONSTRAINT IF EXISTS ck_replay_checkpoint_artifact_checksum;

ALTER TABLE replay_checkpoints
    ADD CONSTRAINT ck_replay_checkpoint_artifact_checksum
    CHECK (snapshot_artifact_checksum ~ '^sha256:[0-9a-f]{64}$')
    NOT VALID;

ALTER TABLE replay_checkpoints
    VALIDATE CONSTRAINT ck_replay_checkpoint_artifact_checksum;

ALTER TABLE replay_checkpoints
    DROP CONSTRAINT IF EXISTS ck_replay_checkpoint_snapshot_checksum;

ALTER TABLE replay_checkpoints
    ADD CONSTRAINT ck_replay_checkpoint_snapshot_checksum
    CHECK (snapshot_checksum ~ '^sha256:[0-9a-f]{64}$')
    NOT VALID;

ALTER TABLE replay_checkpoints
    VALIDATE CONSTRAINT ck_replay_checkpoint_snapshot_checksum;

ALTER TABLE replay_checkpoints
    ALTER COLUMN snapshot_checksum TYPE VARCHAR(71),
    ALTER COLUMN snapshot_checksum SET NOT NULL,
    ALTER COLUMN snapshot_artifact_checksum SET NOT NULL;

-- Preserve the branch-restore lookup index.
CREATE INDEX IF NOT EXISTS idx_replay_checkpoint_artifact_checksum
    ON replay_checkpoints(run_id, snapshot_artifact_checksum);
