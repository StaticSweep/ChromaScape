-- V11: Replay curation and manifest infrastructure.
-- Creates curation tables and scenario_manifests for deterministic replay.

-- Curations
CREATE TABLE IF NOT EXISTS recording_curations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recording_id VARCHAR NOT NULL,
    debug_session_id VARCHAR NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    state VARCHAR NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    role VARCHAR NOT NULL CHECK (role IN ('TRAIN', 'VALIDATION', 'HELD_OUT')),
    schema_version INTEGER NOT NULL DEFAULT 1,
    content_json JSONB NOT NULL,
    content_checksum VARCHAR NOT NULL,
    created_by VARCHAR NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    published_at TIMESTAMP,
    CONSTRAINT uq_recording_curation_version UNIQUE (recording_id, version)
);

-- Curated action labels
CREATE TABLE IF NOT EXISTS curated_actions (
    curation_id UUID NOT NULL REFERENCES recording_curations(id) ON DELETE CASCADE,
    tick BIGINT NOT NULL,
    sequence INTEGER NOT NULL DEFAULT 0,
    status VARCHAR NOT NULL CHECK (status IN ('ACCEPTED_LABEL', 'IGNORED', 'UNSUPPORTED')),
    semantic_action_type VARCHAR,
    target_stable_id VARCHAR,
    arguments_json JSONB,
    evidence_ref JSONB,
    confidence NUMERIC,
    PRIMARY KEY (curation_id, tick, sequence)
);

-- Provenance for each curated field
CREATE TABLE IF NOT EXISTS field_provenance (
    curation_id UUID NOT NULL REFERENCES recording_curations(id) ON DELETE CASCADE,
    json_pointer VARCHAR NOT NULL,
    provenance VARCHAR NOT NULL CHECK (provenance IN ('OBSERVED', 'DERIVED', 'OPERATOR_SUPPLIED', 'DEFAULTED')),
    evidence_ref JSONB,
    derivation_version VARCHAR,
    supplied_by VARCHAR,
    reason VARCHAR,
    PRIMARY KEY (curation_id, json_pointer)
);

-- Scenario manifests
CREATE TABLE IF NOT EXISTS scenario_manifests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    curation_id UUID NOT NULL REFERENCES recording_curations(id),
    recording_id VARCHAR NOT NULL,
    debug_session_id VARCHAR NOT NULL,
    recording_checksum VARCHAR NOT NULL,
    debug_session_checksum VARCHAR NOT NULL,
    compiler_version VARCHAR NOT NULL,
    compiler_schema_version INTEGER NOT NULL,
    xrsps_adapter_version VARCHAR NOT NULL,
    xrsps_build_commit VARCHAR NOT NULL,
    cache_revision VARCHAR NOT NULL,
    content_revision VARCHAR NOT NULL,
    seed VARCHAR NOT NULL,
    fixed_clock_origin TIMESTAMP NOT NULL,
    start_tick BIGINT NOT NULL,
    end_tick BIGINT NOT NULL,
    manifest_json JSONB NOT NULL,
    manifest_checksum VARCHAR UNIQUE NOT NULL,
    completeness VARCHAR NOT NULL CHECK (completeness IN ('COMPLETE', 'INCOMPLETE')),
    publish_state VARCHAR NOT NULL DEFAULT 'DRAFT' CHECK (publish_state IN ('DRAFT', 'PUBLISHED')),
    rejection_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);