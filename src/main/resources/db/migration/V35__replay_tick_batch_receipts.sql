-- V35: durable application-level acknowledgment boundary for Micro6 tick batches.
-- debug_sessions is Flyway-owned since V24, so the foreign key applies unconditionally.
CREATE TABLE IF NOT EXISTS replay_tick_batch_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(100) NOT NULL,
    batch_id UUID NOT NULL,
    tick BIGINT NOT NULL CHECK (tick >= 0),
    payload_checksum VARCHAR(71) NOT NULL CHECK (payload_checksum ~ '^sha256:[0-9a-f]{64}$'),
    schema_name VARCHAR(64) NOT NULL CHECK (schema_name = 'tick-batch/v2'),
    persisted_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_replay_tick_batch_receipt_session_batch UNIQUE (session_id, batch_id),
    CONSTRAINT uq_replay_tick_batch_receipt_session_tick UNIQUE (session_id, tick)
);

DO $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'fk_replay_tick_batch_receipt_session'
        ) THEN
        ALTER TABLE replay_tick_batch_receipts
            ADD CONSTRAINT fk_replay_tick_batch_receipt_session
            FOREIGN KEY (session_id) REFERENCES debug_sessions(session_id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_replay_tick_batch_receipt_session_tick
    ON replay_tick_batch_receipts (session_id, tick);
