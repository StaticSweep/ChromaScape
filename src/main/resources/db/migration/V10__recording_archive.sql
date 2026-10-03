-- V10: Flyway owns recording_sessions and its archived_at column.
-- Hibernate-managed debug tables did not exist when V10 was first written, so an earlier
-- revision guarded the ALTER with to_regclass and silently omitted the column on fresh
-- databases. Nothing has been deployed, so ownership is fixed here: the table is created
-- first (mirroring the RecordingSession entity) and the column/index are applied
-- unconditionally.
CREATE TABLE IF NOT EXISTS recording_sessions (
    id BIGSERIAL PRIMARY KEY,
    recording_id VARCHAR(100) NOT NULL,
    account_name VARCHAR(100),
    script_name VARCHAR(100),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    trigger_type VARCHAR(20) NOT NULL,
    video_path VARCHAR(500),
    duration_ms BIGINT,
    frame_count BIGINT,
    fps INTEGER,
    video_codec VARCHAR(50),
    replay_name VARCHAR(160),
    archived_at TIMESTAMP NULL,
    replay_tags_json TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_recording_sessions_recording_id
    ON recording_sessions (recording_id);
CREATE INDEX IF NOT EXISTS idx_recording_sessions_start_time
    ON recording_sessions (start_time);
CREATE INDEX IF NOT EXISTS idx_recording_sessions_recording_id
    ON recording_sessions (recording_id);

ALTER TABLE recording_sessions
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_recording_sessions_archived_at
    ON recording_sessions (archived_at);
