-- V20: finalization reservation timestamp for fenced finalization cutoffs.
--
-- reserveSuccessfulFinalization / reserveFailureFinalization capture ONE PostgreSQL clock value
-- when the live fence enters FINALIZE_ARTIFACTS / FINALIZE_REPORT. That single capture becomes the
-- authoritative terminal time for report JSON, report entity, run row, attempt row, and artifact
-- finalized_at — the JVM clock never enters terminal evidence.
--
-- No secondary index: nothing selects runs by this column operationally.

ALTER TABLE replay_runs
    ADD COLUMN IF NOT EXISTS finalization_reserved_at TIMESTAMP;
