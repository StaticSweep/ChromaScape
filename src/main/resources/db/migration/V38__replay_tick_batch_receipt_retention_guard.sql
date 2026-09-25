-- V38: harden receipt retention for databases that already applied V35/V37.
-- V35 is immutable and V37 may already have been applied in an external environment.
-- debug_sessions is Flyway-owned since V24. All references resolve through the CURRENT
-- search_path schema so isolated test contexts converge too.
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

        ALTER TABLE replay_tick_batch_receipts
            ADD CONSTRAINT fk_replay_tick_batch_receipt_session
            FOREIGN KEY (session_id)
            REFERENCES debug_sessions(session_id)
            ON DELETE CASCADE;
    END IF;
END $$;

-- The unique (session_id, tick) constraint already supplies the required index.
DROP INDEX IF EXISTS idx_replay_tick_batch_receipt_session_tick;
