-- Forward current-schema reconciliation. V1-V7 remain unchanged.
-- Existing SC-1976 contract from db/migration/V70__sc1976_recording_identity.sql.
ALTER TABLE debug_sessions ADD COLUMN capture_identity JSONB;
ALTER TABLE debug_sessions ADD CONSTRAINT ck_debug_sessions_capture_identity
  CHECK (capture_identity IS NULL OR
    (jsonb_typeof(capture_identity) = 'object'
      AND octet_length(capture_identity::text) <= 16384));

-- Preserve the bounded SC-751 observation-quality contract from canonical V69.
-- Historical snapshots remain unknown; raw events are not inferred action receipts.
ALTER TABLE tick_snapshots ADD COLUMN capture_quality JSONB;
ALTER TABLE tick_snapshots ADD CONSTRAINT ck_tick_snapshots_capture_quality_size
  CHECK (capture_quality IS NULL OR
    (jsonb_typeof(capture_quality) = 'object'
      AND octet_length(capture_quality::text) <= 262144));

-- Preserve canonical V68's optional SC-1976 sections without inventing old evidence.
ALTER TABLE tick_snapshots
  ADD COLUMN inputs JSONB,
  ADD COLUMN tiles JSONB,
  ADD COLUMN hazards JSONB,
  ADD COLUMN phase JSONB;
ALTER TABLE tick_snapshots ADD CONSTRAINT ck_tick_snapshots_combat_row_json_sizes
  CHECK (
    (inputs IS NULL OR octet_length(inputs::text) <= 262144)
    AND (tiles IS NULL OR octet_length(tiles::text) <= 262144)
    AND (hazards IS NULL OR octet_length(hazards::text) <= 262144)
    AND (phase IS NULL OR octet_length(phase::text) <= 262144));

-- Same committed source cursor as canonical V72. No historical authority is invented.
ALTER TABLE replay_attempts ADD COLUMN native_source_cursor JSONB;
ALTER TABLE replay_attempts ADD CONSTRAINT replay_attempts_native_source_cursor_check
  CHECK (native_source_cursor IS NULL OR
    ((jsonb_typeof(native_source_cursor) = 'object'
      AND native_source_cursor ->> 'schema' = 'xrsps-source-cursor/v1'
      AND octet_length(native_source_cursor::text) <= 1024) IS TRUE));

-- Only empty, explicitly disposable schemas may be bootstrapped by the strict guard.
ALTER TABLE ai_player_schema_identity DISABLE TRIGGER ai_player_identity_immutable;
ALTER TABLE ai_player_schema_identity DROP CONSTRAINT ai_player_schema_identity_identity_check;
UPDATE ai_player_schema_identity SET identity = 'sc276-current-v2';
ALTER TABLE ai_player_schema_identity ADD CONSTRAINT ai_player_schema_identity_identity_check
  CHECK (identity = 'sc276-current-v2');
ALTER TABLE ai_player_schema_identity ENABLE TRIGGER ai_player_identity_immutable;
