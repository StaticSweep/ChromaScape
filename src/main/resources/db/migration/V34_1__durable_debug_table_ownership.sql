-- Additive ownership prerequisite for durable receipt migrations on fresh databases.
-- Preserve the checked-in V10/V24 checksums; old installations may already have applied them.
-- CREATE IF NOT EXISTS retains all pre-existing Hibernate-owned data.
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
CREATE TABLE IF NOT EXISTS debug_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    account_name VARCHAR(100),
    script_name VARCHAR(100),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    start_tick BIGINT NOT NULL,
    end_tick BIGINT,
    recording_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    lifecycle_state VARCHAR(20),
    failure_code VARCHAR(50),
    failure_reason VARCHAR(500),
    failure_context VARCHAR(2000),
    last_state_at TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_debug_sessions_session_id
    ON debug_sessions (session_id);
CREATE INDEX IF NOT EXISTS idx_debug_sessions_recording_id
    ON debug_sessions (recording_id);
CREATE INDEX IF NOT EXISTS idx_debug_sessions_start_time
    ON debug_sessions (start_time);


DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_replay_recording_actions_session'
          AND conrelid = 'replay_recording_actions'::regclass
    ) THEN
        ALTER TABLE replay_recording_actions
            ADD CONSTRAINT fk_replay_recording_actions_session
            FOREIGN KEY (session_id) REFERENCES debug_sessions(session_id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_replay_recording_actions_recording'
          AND conrelid = 'replay_recording_actions'::regclass
    ) THEN
        ALTER TABLE replay_recording_actions
            ADD CONSTRAINT fk_replay_recording_actions_recording
            FOREIGN KEY (recording_id) REFERENCES recording_sessions(recording_id) ON DELETE RESTRICT;
    END IF;
END $$;
