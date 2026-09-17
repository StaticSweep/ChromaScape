-- V39: durable RSProx packet observations for SC-1618.
-- One row per rsprox-packet/v1 envelope, written in the tick-batch transaction before ACK.
-- Unique (session_id, source_session_id, packet_sequence) gives retry idempotency.
-- debug_sessions is Flyway-owned since V24, so the foreign key applies unconditionally.
-- The unqualified reference resolves through the CURRENT search_path schema, matching V24/V35.
CREATE TABLE IF NOT EXISTS packet_observations (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    tick BIGINT NOT NULL,
    source_session_id VARCHAR(100) NOT NULL,
    packet_sequence BIGINT NOT NULL,
    captured_at TIMESTAMP,
    capture_delta_millis BIGINT,
    direction VARCHAR(20) NOT NULL,
    opcode INTEGER NOT NULL,
    protocol_name VARCHAR(128) NOT NULL,
    encoded_size INTEGER NOT NULL,
    decoded_type VARCHAR(128),
    decoded_payload JSONB,
    sanitized_payload BYTEA,
    decode_status VARCHAR(20) NOT NULL,
    decode_error VARCHAR(512),
    synthetic BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_packet_observations_session_source_sequence
        UNIQUE (session_id, source_session_id, packet_sequence),
    CONSTRAINT chk_packet_observations_direction
        CHECK (direction IN ('CLIENT_TO_SERVER', 'SERVER_TO_CLIENT')),
    CONSTRAINT chk_packet_observations_status
        CHECK (decode_status IN ('DECODED', 'UNKNOWN_PROTOCOL', 'DECODE_ERROR', 'GAP'))
);

CREATE INDEX IF NOT EXISTS idx_packet_observations_session_tick_seq
    ON packet_observations (session_id, tick, packet_sequence);
CREATE INDEX IF NOT EXISTS idx_packet_observations_direction
    ON packet_observations (session_id, direction);
CREATE INDEX IF NOT EXISTS idx_packet_observations_protocol
    ON packet_observations (session_id, protocol_name);
CREATE INDEX IF NOT EXISTS idx_packet_observations_status
    ON packet_observations (session_id, decode_status);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE c.conname = 'fk_packet_observations_session'
          AND t.relname = 'packet_observations'
    ) THEN
        ALTER TABLE packet_observations
            ADD CONSTRAINT fk_packet_observations_session
            FOREIGN KEY (session_id)
            REFERENCES debug_sessions(session_id)
            ON DELETE CASCADE;
    END IF;
END $$;
