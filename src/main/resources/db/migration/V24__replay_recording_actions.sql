-- V24: immutable typed semantic recording actions for replay.
-- V22/V23 are already applied histories; this migration is additive and must not
-- edit or renumber an earlier migration.
--
-- Flyway owns debug_sessions here: earlier revisions skipped the foreign keys with
-- to_regclass guards when Hibernate-owned tables were absent, silently omitting them on
-- fresh databases. Nothing has been deployed, so the referenced tables are created first
-- and the constraints are applied unconditionally. All recording/debug references resolve
-- through the CURRENT search_path schema (isolated test contexts use a per-context schema,
-- not public), so constraints target unqualified tables.

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

CREATE TABLE IF NOT EXISTS replay_recording_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(100) NOT NULL,
    recording_id VARCHAR(100),
    tick BIGINT NOT NULL CHECK (tick >= 0),
    sequence INTEGER NOT NULL CHECK (sequence >= 0),
    schema_name VARCHAR(64) NOT NULL CHECK (schema_name = 'recording-action/v1'),
    action_type VARCHAR(64) NOT NULL CHECK (action_type IN (
        'WAIT_UNTIL_TICK', 'WALK_TO', 'INTERACT_NPC', 'INTERACT_OBJECT',
        'INTERACT_GROUND_ITEM', 'INTERACT_INVENTORY_ITEM', 'INTERACT_WIDGET',
        'SELECT_DIALOGUE_OPTION', 'CAST_SPELL', 'EQUIP_ITEM')),
    target_kind VARCHAR(32) CHECK (target_kind IS NULL OR target_kind IN (
        'NPC', 'OBJECT', 'GROUND_ITEM', 'INVENTORY_ITEM', 'WIDGET', 'TILE', 'PLAYER')),
    target_stable_id VARCHAR(255),
    target_json JSONB,
    arguments_json JSONB NOT NULL,
    source VARCHAR(64) NOT NULL CHECK (source IN (
        'MICRO6_SEMANTIC_DISPATCH', 'MICRO6_LEGACY_CONVERSION',
        'OPERATOR_SUPPLIED', 'DEFAULTED')),
    confidence NUMERIC(4,3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    captured_at TIMESTAMP NOT NULL,
    ingested_at TIMESTAMP NOT NULL DEFAULT now(),
    content_checksum VARCHAR(71) NOT NULL CHECK (content_checksum ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT uq_replay_recording_action_session_tick_sequence
        UNIQUE (session_id, tick, sequence),
    CONSTRAINT replay_recording_action_target_check CHECK (
        action_type = 'WAIT_UNTIL_TICK'
        OR (target_kind IS NOT NULL AND target_stable_id IS NOT NULL AND btrim(target_stable_id) <> '')
    ),
    CONSTRAINT replay_recording_action_target_size_check CHECK (
        target_json IS NULL OR octet_length(target_json::text) <= 65536
    ),
    CONSTRAINT replay_recording_action_arguments_size_check CHECK (
        octet_length(arguments_json::text) <= 65536
    )
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_replay_recording_actions_session'
    ) THEN
        ALTER TABLE replay_recording_actions
            ADD CONSTRAINT fk_replay_recording_actions_session
            FOREIGN KEY (session_id) REFERENCES debug_sessions(session_id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_replay_recording_actions_recording'
    ) THEN
        ALTER TABLE replay_recording_actions
            ADD CONSTRAINT fk_replay_recording_actions_recording
            FOREIGN KEY (recording_id) REFERENCES recording_sessions(recording_id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_replay_recording_actions_recording_tick
    ON replay_recording_actions (recording_id, tick, sequence);
CREATE INDEX IF NOT EXISTS idx_replay_recording_actions_session_tick
    ON replay_recording_actions (session_id, tick, sequence);
