-- Exact native source progress is independent of steps, hash samples and branch inheritance.
-- Historical attempts remain unknown; never infer or backfill their action counts.
ALTER TABLE replay_attempts ADD COLUMN native_source_cursor JSONB;
ALTER TABLE replay_attempts ADD CONSTRAINT replay_attempts_native_source_cursor_check
  CHECK (native_source_cursor IS NULL OR
    ((jsonb_typeof(native_source_cursor) = 'object'
      AND native_source_cursor ->> 'schema' = 'xrsps-source-cursor/v1'
      AND octet_length(native_source_cursor::text) <= 1024) IS TRUE));
