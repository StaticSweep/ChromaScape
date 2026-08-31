ALTER TABLE recording_sessions
  ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_recording_sessions_archived_at
  ON recording_sessions (archived_at);
