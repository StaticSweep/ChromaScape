-- Legacy rows stay NULL; raw CLIENT_EVENT evidence remains distinct from dispatched actions.
ALTER TABLE IF EXISTS tick_snapshots ADD COLUMN IF NOT EXISTS capture_quality JSONB;
DO $$
BEGIN
  IF to_regclass(current_schema() || '.tick_snapshots') IS NOT NULL THEN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
      WHERE conname = 'ck_tick_snapshots_capture_quality_size'
      AND conrelid = 'tick_snapshots'::regclass) THEN
      ALTER TABLE tick_snapshots ADD CONSTRAINT ck_tick_snapshots_capture_quality_size
        CHECK (capture_quality IS NULL OR
          (jsonb_typeof(capture_quality) = 'object' AND octet_length(capture_quality::text) <= 262144));
    END IF;
  END IF;
END $$;
