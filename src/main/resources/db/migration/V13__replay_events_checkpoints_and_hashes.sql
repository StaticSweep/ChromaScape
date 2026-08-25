-- V13: Replay events, checkpoints, and state hash samples.
-- Durable ordered event stream with checkpoint and hash-chain tracking.

-- Ordered replay events
CREATE TABLE IF NOT EXISTS replay_events (
    run_id UUID NOT NULL REFERENCES replay_runs(id) ON DELETE CASCADE,
    attempt_id UUID NOT NULL REFERENCES replay_attempts(id) ON DELETE CASCADE,
    ordinal BIGINT NOT NULL,
    tick BIGINT NOT NULL,
    sequence INTEGER NOT NULL DEFAULT 0,
    event_type VARCHAR NOT NULL,
    category VARCHAR NOT NULL,
    payload JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, ordinal)
);

-- Immutable checkpoints
CREATE TABLE IF NOT EXISTS replay_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES replay_runs(id) ON DELETE CASCADE,
    attempt_id UUID NOT NULL REFERENCES replay_attempts(id) ON DELETE CASCADE,
    tick BIGINT NOT NULL,
    action_index INTEGER NOT NULL DEFAULT 0,
    branch_key VARCHAR NOT NULL DEFAULT 'default',
    snapshot_checksum VARCHAR,
    snapshot_artifact_id VARCHAR,
    state_hash VARCHAR NOT NULL,
    chain_hash VARCHAR NOT NULL,
    parent_checkpoint_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_checkpoint_branch UNIQUE (run_id, branch_key, tick, action_index)
);

-- Per-tick state hash samples
CREATE TABLE IF NOT EXISTS state_hash_samples (
    run_id UUID NOT NULL REFERENCES replay_runs(id) ON DELETE CASCADE,
    tick BIGINT NOT NULL,
    sequence INTEGER NOT NULL DEFAULT 0,
    projection_version INTEGER NOT NULL DEFAULT 1,
    state_hash VARCHAR NOT NULL,
    chain_hash VARCHAR NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, tick, sequence)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_replay_events_run_ordinal ON replay_events(run_id, ordinal);
CREATE INDEX IF NOT EXISTS idx_replay_checkpoints_run_tick ON replay_checkpoints(run_id, branch_key, tick);
CREATE INDEX IF NOT EXISTS idx_state_hash_samples_run_tick ON state_hash_samples(run_id, tick, sequence);