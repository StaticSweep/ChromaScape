-- V25: additive metadata for recording conversion, fidelity, and read-only trace viewing.
-- Existing v1 rows remain readable and are explicitly classified as best-effort synthetic data.

ALTER TABLE scenario_manifests
    ADD COLUMN IF NOT EXISTS manifest_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS fidelity VARCHAR(32) NOT NULL DEFAULT 'SYNTHETIC_CONTRACT',
    ADD COLUMN IF NOT EXISTS conversion_quality VARCHAR(32) NOT NULL DEFAULT 'BEST_EFFORT',
    ADD COLUMN IF NOT EXISTS action_coverage_pct NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS gap_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS approximation_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS viewer_trace_checksum VARCHAR(71),
    ADD COLUMN IF NOT EXISTS viewer_trace_frame_count BIGINT;

ALTER TABLE replay_runs
    ADD COLUMN IF NOT EXISTS manifest_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS fidelity VARCHAR(32) NOT NULL DEFAULT 'SYNTHETIC_CONTRACT',
    ADD COLUMN IF NOT EXISTS conversion_quality VARCHAR(32) NOT NULL DEFAULT 'BEST_EFFORT',
    ADD COLUMN IF NOT EXISTS action_coverage_pct NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS gap_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS approximation_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS viewer_trace_checksum VARCHAR(71),
    ADD COLUMN IF NOT EXISTS viewer_trace_frame_count BIGINT;

ALTER TABLE replay_reports
    ADD COLUMN IF NOT EXISTS manifest_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS fidelity VARCHAR(32) NOT NULL DEFAULT 'SYNTHETIC_CONTRACT',
    ADD COLUMN IF NOT EXISTS conversion_quality VARCHAR(32) NOT NULL DEFAULT 'BEST_EFFORT',
    ADD COLUMN IF NOT EXISTS action_coverage_pct NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS gap_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS approximation_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS viewer_trace_checksum VARCHAR(71),
    ADD COLUMN IF NOT EXISTS viewer_trace_frame_count BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_scenario_manifest_v2_fidelity'
          AND conrelid = 'scenario_manifests'::regclass
    ) THEN
        ALTER TABLE scenario_manifests ADD CONSTRAINT ck_scenario_manifest_v2_fidelity
            CHECK (fidelity IN ('SYNTHETIC_CONTRACT', 'XRSPS_AUTHORITATIVE'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_replay_run_v2_fidelity'
          AND conrelid = 'replay_runs'::regclass
    ) THEN
        ALTER TABLE replay_runs ADD CONSTRAINT ck_replay_run_v2_fidelity
            CHECK (fidelity IN ('SYNTHETIC_CONTRACT', 'XRSPS_AUTHORITATIVE'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_replay_report_v2_fidelity'
          AND conrelid = 'replay_reports'::regclass
    ) THEN
        ALTER TABLE replay_reports ADD CONSTRAINT ck_replay_report_v2_fidelity
            CHECK (fidelity IN ('SYNTHETIC_CONTRACT', 'XRSPS_AUTHORITATIVE'));
    END IF;
END $$;

ALTER TABLE scenario_manifests
    ADD CONSTRAINT ck_scenario_manifest_v2_coverage
    CHECK (action_coverage_pct >= 0 AND action_coverage_pct <= 100);

ALTER TABLE replay_runs
    ADD CONSTRAINT ck_replay_run_v2_coverage
    CHECK (action_coverage_pct >= 0 AND action_coverage_pct <= 100);

ALTER TABLE replay_reports
    ADD CONSTRAINT ck_replay_report_v2_coverage
    CHECK (action_coverage_pct >= 0 AND action_coverage_pct <= 100);
