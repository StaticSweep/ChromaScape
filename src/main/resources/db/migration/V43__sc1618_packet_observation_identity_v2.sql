-- SC-1618: separate v2 observation identity from original capture identity.
-- V39 remains the historical v1 migration. Existing rows are explicitly backfilled as v1 so old
-- recordings retain their original meaning and remain readable after this migration.

ALTER TABLE packet_observations
    ADD COLUMN IF NOT EXISTS packet_schema VARCHAR(32),
    ADD COLUMN IF NOT EXISTS observation_sequence BIGINT;

UPDATE packet_observations
SET packet_schema = 'rsprox-packet/v1'
WHERE packet_schema IS NULL;

UPDATE packet_observations
SET observation_sequence = packet_sequence
WHERE observation_sequence IS NULL;

ALTER TABLE packet_observations
    ALTER COLUMN packet_schema SET NOT NULL,
    ALTER COLUMN observation_sequence SET NOT NULL,
    ALTER COLUMN packet_sequence DROP NOT NULL;

ALTER TABLE packet_observations
    DROP CONSTRAINT IF EXISTS uq_packet_observations_session_source_sequence;

ALTER TABLE packet_observations
    DROP CONSTRAINT IF EXISTS chk_packet_observations_schema,
    ADD CONSTRAINT chk_packet_observations_schema
        CHECK (packet_schema IN ('rsprox-packet/v1', 'rsprox-packet/v2')),
    DROP CONSTRAINT IF EXISTS chk_packet_observations_identity,
    ADD CONSTRAINT chk_packet_observations_identity
        CHECK (packet_schema = 'rsprox-packet/v2' OR packet_sequence IS NOT NULL),
    ADD CONSTRAINT uq_packet_observations_session_source_sequence
        UNIQUE (session_id, source_session_id, packet_schema, observation_sequence);

DROP INDEX IF EXISTS idx_packet_observations_session_tick_seq;
CREATE INDEX IF NOT EXISTS idx_packet_observations_session_tick_seq
    ON packet_observations (
        session_id, tick, source_session_id, packet_schema, observation_sequence, id);
