-- V14: Replay mismatches, reports, and artifacts.
-- Mismatch evidence, immutable final reports, and content-addressed artifact tracking.

-- Mismatch evidence
CREATE TABLE IF NOT EXISTS replay_mismatches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES replay_runs(id) ON DELETE CASCADE,
    level VARCHAR NOT NULL CHECK (level IN ('HARD', 'SOFT', 'INFO')),
    rule_id VARCHAR NOT NULL,
    first_tick BIGINT NOT NULL,
    sequence INTEGER NOT NULL DEFAULT 0,
    domain VARCHAR NOT NULL,
    json_pointer VARCHAR,
    expected_value VARCHAR,
    actual_value VARCHAR,
    related_action_ordinal INTEGER,
    checkpoint_id UUID REFERENCES replay_checkpoints(id),
    causal_parent_id UUID,
    cascade_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Immutable replay reports (one per run)
CREATE TABLE IF NOT EXISTS replay_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL UNIQUE REFERENCES replay_runs(id),
    attempt_id UUID NOT NULL REFERENCES replay_attempts(id),
    mode VARCHAR NOT NULL,
    manifest_checksum VARCHAR NOT NULL,
    outcome VARCHAR NOT NULL CHECK (outcome IN ('PASSED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'INFRASTRUCTURE_FAILURE')),
    executed_action_count INTEGER NOT NULL DEFAULT 0,
    checkpoint_count INTEGER NOT NULL DEFAULT 0,
    mismatch_hard_count INTEGER NOT NULL DEFAULT 0,
    mismatch_soft_count INTEGER NOT NULL DEFAULT 0,
    mismatch_info_count INTEGER NOT NULL DEFAULT 0,
    final_state_hash VARCHAR,
    final_chain_hash VARCHAR,
    terminal_reason VARCHAR,
    xrsps_adapter_version VARCHAR,
    xrsps_build_commit VARCHAR,
    cache_revision VARCHAR,
    content_revision VARCHAR,
    report_json JSONB,
    report_checksum VARCHAR NOT NULL UNIQUE,
    control_allowed BOOLEAN NOT NULL DEFAULT false CHECK (control_allowed = false),
    started_at TIMESTAMP,
    terminal_at TIMESTAMP,
    finalized_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Content-addressed artifact references
CREATE TABLE IF NOT EXISTS artifact_refs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES replay_runs(id) ON DELETE CASCADE,
    kind VARCHAR NOT NULL CHECK (kind IN ('MANIFEST', 'SNAPSHOT', 'TRACE', 'REPORT')),
    checksum VARCHAR NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    media_type VARCHAR,
    state VARCHAR NOT NULL DEFAULT 'STAGING' CHECK (state IN ('STAGING', 'FINALIZED', 'CORRUPT', 'TOMBSTONED')),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    finalized_at TIMESTAMP,
    CONSTRAINT uq_artifact_checksum UNIQUE (checksum)
);