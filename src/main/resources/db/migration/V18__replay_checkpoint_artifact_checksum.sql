-- V18: replay_checkpoints.snapshot_artifact_id -> snapshot_artifact_checksum
--
-- The column already stores a full serialized-artifact content address
-- (sha256:<hex>), never a row id. The rename makes the schema tell the truth,
-- preserves every existing value, enforces the SHA-256 format, and adds the
-- (run_id, snapshot_artifact_checksum) lookup index used by branch restore.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'replay_checkpoints'
          AND column_name = 'snapshot_artifact_id'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'replay_checkpoints'
          AND column_name = 'snapshot_artifact_checksum'
    ) THEN
        ALTER TABLE replay_checkpoints
            RENAME COLUMN snapshot_artifact_id
            TO snapshot_artifact_checksum;
    END IF;
END $$;

ALTER TABLE replay_checkpoints
    ADD COLUMN IF NOT EXISTS snapshot_artifact_checksum VARCHAR(71);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_replay_checkpoint_artifact_checksum'
          AND conrelid = 'replay_checkpoints'::regclass
    ) THEN
        ALTER TABLE replay_checkpoints
            ADD CONSTRAINT ck_replay_checkpoint_artifact_checksum
            CHECK (
                snapshot_artifact_checksum IS NULL
                OR snapshot_artifact_checksum ~ '^sha256:[0-9a-f]{64}$'
            ) NOT VALID;
    END IF;
END $$;

ALTER TABLE replay_checkpoints
    VALIDATE CONSTRAINT ck_replay_checkpoint_artifact_checksum;

CREATE INDEX IF NOT EXISTS idx_replay_checkpoint_artifact_checksum
    ON replay_checkpoints(run_id, snapshot_artifact_checksum);
