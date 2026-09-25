-- V17: Permit content-address reuse across runs.
--
-- V14 made artifact_refs.checksum globally unique while each row also
-- belongs to exactly one run. Every replay of the same manifest produces
-- identical snapshot/trace/report content addresses, so the second run hit
-- uq_artifact_checksum. The final content store stays globally
-- content-addressed on disk; the table is redefined as per-run references.

ALTER TABLE artifact_refs DROP CONSTRAINT IF EXISTS uq_artifact_checksum;

-- One reference per (run, kind, checksum): re-staging identical content in
-- the same run is idempotent, distinct runs may share content freely.
CREATE UNIQUE INDEX IF NOT EXISTS uq_artifact_run_kind_checksum
    ON artifact_refs(run_id, kind, checksum);

-- Global checksum lookups (branch restore resolves parent snapshots).
CREATE INDEX IF NOT EXISTS idx_artifact_checksum
    ON artifact_refs(checksum);
