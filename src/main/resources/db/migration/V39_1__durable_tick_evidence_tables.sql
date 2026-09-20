-- Make the receipt's complete transactional evidence set Flyway-owned on fresh databases.
-- Existing Hibernate-created tables and rows are retained; no historical migrations are edited.
CREATE TABLE IF NOT EXISTS tick_snapshots (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    tick BIGINT NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    player_state JSONB NOT NULL,
    inventory JSONB,
    equipment JSONB,
    threats JSONB,
    projectiles JSONB,
    cooldowns JSONB,
    quest JSONB,
    quest_helper JSONB,
    dialogue JSONB,
    spells JSONB,
    vars JSONB,
    npcs JSONB,
    game_objects JSONB,
    ground_objects JSONB,
    ground_items JSONB,
    players JSONB,
    widgets JSONB,
    schema_version INTEGER
);
CREATE INDEX IF NOT EXISTS idx_tick_snapshots_session ON tick_snapshots (session_id);
CREATE INDEX IF NOT EXISTS idx_tick_snapshots_session_tick ON tick_snapshots (session_id, tick);

ALTER TABLE tick_snapshots
    ADD COLUMN IF NOT EXISTS dialogue JSONB,
    ADD COLUMN IF NOT EXISTS spells JSONB,
    ADD COLUMN IF NOT EXISTS vars JSONB;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_tick_snapshots_replay_state_json_sizes_v2'
          AND conrelid = 'tick_snapshots'::regclass
    ) THEN
        ALTER TABLE tick_snapshots
            ADD CONSTRAINT ck_tick_snapshots_replay_state_json_sizes_v2 CHECK (
                (dialogue IS NULL OR octet_length(dialogue::text) <= 262144)
                AND (spells IS NULL OR octet_length(spells::text) <= 262144)
                AND (vars IS NULL OR octet_length(vars::text) <= 262144)
            );
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS tick_logs (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    tick BIGINT NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    subsystem VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    event_type VARCHAR(100),
    message TEXT NOT NULL,
    details JSONB,
    correlation_id UUID
);
CREATE INDEX IF NOT EXISTS idx_tick_logs_session_tick ON tick_logs (session_id, tick);
CREATE INDEX IF NOT EXISTS idx_tick_logs_severity ON tick_logs (session_id, severity);

CREATE TABLE IF NOT EXISTS tick_overlays (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    tick BIGINT NOT NULL,
    overlay_type VARCHAR(50) NOT NULL,
    data JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tick_overlays_session_tick ON tick_overlays (session_id, tick);

CREATE TABLE IF NOT EXISTS timeline_markers (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    tick BIGINT NOT NULL,
    marker_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20),
    label VARCHAR(255),
    description TEXT,
    log_id BIGINT,
    is_auto_generated BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_timeline_markers_session_tick
    ON timeline_markers (session_id, tick);
