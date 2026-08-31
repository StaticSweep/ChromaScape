-- SC-1452: reliability comparability keys contain several sha256 identities plus runtime
-- dimensions and legitimately exceed the historical varchar(255) column. Preserve the complete
-- key because it participates in snapshot idempotency; truncation would merge distinct groups.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'script_stabilization_snapshot'
          AND column_name = 'group_key'
    ) THEN
        ALTER TABLE script_stabilization_snapshot
            ALTER COLUMN group_key TYPE varchar(1024);
    END IF;
END
$$;
