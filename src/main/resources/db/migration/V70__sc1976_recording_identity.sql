-- Additive: historical sessions remain explicitly unknown. No provenance backfill.
ALTER TABLE debug_sessions ADD COLUMN IF NOT EXISTS capture_identity JSONB;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
    WHERE conname = 'ck_debug_sessions_capture_identity'
      AND conrelid = 'debug_sessions'::regclass) THEN
    ALTER TABLE debug_sessions ADD CONSTRAINT ck_debug_sessions_capture_identity
      CHECK (capture_identity IS NULL OR
        (jsonb_typeof(capture_identity) = 'object'
          AND octet_length(capture_identity::text) <= 16384));
  END IF;
END $$;
