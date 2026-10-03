-- V37: align durable tick-batch receipts with debug-session retention.
-- V35 is already part of the migration history and is intentionally left immutable.
-- debug_sessions is Flyway-owned since V24, so this runs unconditionally. All references
-- resolve through the CURRENT search_path schema so isolated test contexts converge too.
DO $$
BEGIN
    IF to_regclass('replay_tick_batch_receipts') IS NOT NULL THEN
        IF EXISTS (
            SELECT 1
              FROM pg_constraint c
              JOIN pg_class table_ref ON table_ref.oid = c.conrelid
              JOIN pg_namespace schema_ref ON schema_ref.oid = table_ref.relnamespace
              WHERE c.conname = 'fk_replay_tick_batch_receipt_session'
                AND schema_ref.nspname = current_schema()
                AND table_ref.relname = 'replay_tick_batch_receipts'
        ) THEN
            ALTER TABLE replay_tick_batch_receipts
                DROP CONSTRAINT fk_replay_tick_batch_receipt_session;
        END IF;

        IF NOT EXISTS (
            SELECT 1
              FROM pg_constraint c
              JOIN pg_class table_ref ON table_ref.oid = c.conrelid
              JOIN pg_namespace schema_ref ON schema_ref.oid = table_ref.relnamespace
              WHERE c.conname = 'fk_replay_tick_batch_receipt_session'
                AND schema_ref.nspname = current_schema()
                AND table_ref.relname = 'replay_tick_batch_receipts'
        ) THEN
            ALTER TABLE replay_tick_batch_receipts
                ADD CONSTRAINT fk_replay_tick_batch_receipt_session
                FOREIGN KEY (session_id)
                REFERENCES debug_sessions(session_id)
                ON DELETE CASCADE;
        END IF;
    END IF;
END $$;

-- uq_replay_tick_batch_receipt_session_tick already owns an equivalent index.
DROP INDEX IF EXISTS idx_replay_tick_batch_receipt_session_tick;
