-- V12: Replay suites and runs.
-- Creates suite/test-case grouping and durable run infrastructure.

-- Older ChromaScape builds let Hibernate create a different replay_runs table
-- (BIGINT id plus run_id/session_id/run_type columns).  CREATE TABLE IF NOT
-- EXISTS would silently keep that incompatible table and the replay_attempts
-- foreign key below would then fail (UUID -> BIGINT).  Preserve that legacy
-- table before installing the current UUID-backed schema.  Refuse to rename an
-- unknown shape so a genuinely unexpected schema still fails closed.
DO $$
DECLARE
    replay_run_id_type TEXT;
    legacy_shape BOOLEAN;
BEGIN
    SELECT data_type
      INTO replay_run_id_type
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'replay_runs'
       AND column_name = 'id';

    IF replay_run_id_type IS NOT NULL AND replay_run_id_type <> 'uuid' THEN
        SELECT replay_run_id_type = 'bigint'
               AND EXISTS (
                   SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'replay_runs' AND column_name = 'run_id'
               )
               AND EXISTS (
                   SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'replay_runs' AND column_name = 'session_id'
               )
               AND EXISTS (
                   SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'replay_runs' AND column_name = 'run_type'
               )
          INTO legacy_shape;

        IF NOT legacy_shape THEN
            RAISE EXCEPTION
                'V12 cannot migrate replay_runs: expected UUID id or recognized legacy BIGINT schema, found id type %',
                replay_run_id_type;
        END IF;

        IF to_regclass(current_schema() || '.replay_runs_legacy_v12') IS NOT NULL THEN
            RAISE EXCEPTION
                'V12 cannot archive legacy replay_runs: replay_runs_legacy_v12 already exists';
        END IF;

        ALTER TABLE replay_runs RENAME TO replay_runs_legacy_v12;

        -- PostgreSQL does not rename the primary-key index when its table is
        -- renamed.  Free the conventional name needed by the new table.
        IF to_regclass(current_schema() || '.replay_runs_pkey') IS NOT NULL THEN
            ALTER INDEX replay_runs_pkey RENAME TO replay_runs_legacy_v12_pkey;
        END IF;
    END IF;
END $$;

-- Replay suites (mutable grouping metadata)
CREATE TABLE IF NOT EXISTS replay_suites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR NOT NULL,
    description VARCHAR,
    created_by VARCHAR NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Immutable ordered suite versions
CREATE TABLE IF NOT EXISTS replay_suite_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id UUID NOT NULL REFERENCES replay_suites(id) ON DELETE CASCADE,
    version INTEGER NOT NULL CHECK (version > 0),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_suite_version UNIQUE (suite_id, version)
);

-- Immutable cases within a suite version
CREATE TABLE IF NOT EXISTS replay_cases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_version_id UUID NOT NULL REFERENCES replay_suite_versions(id) ON DELETE CASCADE,
    manifest_id UUID NOT NULL REFERENCES scenario_manifests(id),
    mode VARCHAR NOT NULL CHECK (mode IN ('POLICY_REPLAY', 'CAUSAL_SIMULATION')),
    case_index INTEGER NOT NULL,
    expected_terminal_policy VARCHAR,
    tolerances_json JSONB,
    stop_policy VARCHAR NOT NULL DEFAULT 'STOP_ON_FIRST_HARD',
    CONSTRAINT uq_case_in_version UNIQUE (suite_version_id, case_index)
);

-- Replay runs
CREATE TABLE IF NOT EXISTS replay_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mode VARCHAR NOT NULL CHECK (mode IN ('OBSERVED_PLAYBACK', 'POLICY_REPLAY', 'CAUSAL_SIMULATION')),
    manifest_id UUID NOT NULL REFERENCES scenario_manifests(id),
    manifest_checksum VARCHAR NOT NULL,
    owner_id VARCHAR NOT NULL,
    idempotency_key VARCHAR NOT NULL,
    state VARCHAR NOT NULL DEFAULT 'QUEUED'
        CHECK (state IN ('QUEUED', 'CLAIMED', 'STARTING', 'RUNNING', 'CANCELLING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT')),
    phase VARCHAR NOT NULL DEFAULT 'PREFLIGHT',
    outcome VARCHAR, -- set at terminal time: PASSED, FAILED, CANCELLED, TIMED_OUT, INFRASTRUCTURE_FAILURE
    terminal_reason VARCHAR,
    xrsps_adapter_version VARCHAR,
    xrsps_build_commit VARCHAR,
    cache_revision VARCHAR,
    content_revision VARCHAR,
    stop_policy VARCHAR NOT NULL DEFAULT 'STOP_ON_FIRST_HARD',
    control_allowed BOOLEAN NOT NULL DEFAULT false CHECK (control_allowed = false),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    started_at TIMESTAMP,
    terminal_at TIMESTAMP,
    CONSTRAINT uq_run_idempotency UNIQUE (owner_id, idempotency_key)
);

-- Run attempts
CREATE TABLE IF NOT EXISTS replay_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES replay_runs(id) ON DELETE CASCADE,
    attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
    job_id VARCHAR,
    lease_owner VARCHAR,
    fencing_generation INTEGER NOT NULL CHECK (fencing_generation > 0),
    lease_expires_at TIMESTAMP,
    heartbeat_at TIMESTAMP,
    worker_identity VARCHAR,
    state VARCHAR NOT NULL DEFAULT 'QUEUED',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    started_at TIMESTAMP,
    terminal_at TIMESTAMP,
    CONSTRAINT uq_attempt_run_ord UNIQUE (run_id, attempt_no)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_replay_runs_state_created ON replay_runs(state, created_at);
CREATE INDEX IF NOT EXISTS idx_replay_attempts_state_lease ON replay_attempts(state, lease_expires_at);
