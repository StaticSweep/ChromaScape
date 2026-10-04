-- V63: SC-275 causal restore bindings (renumbered above current main V62).
-- CHAIN artifacts carry the bound transition-prefix frames for a checkpoint;
-- replay_checkpoints gains the restore context document and the chain
-- artifact address. All additions are nullable: historical rows restore
-- through the legacy path guard and never silently gain v2 bindings.
ALTER TABLE artifact_refs DROP CONSTRAINT IF EXISTS artifact_refs_kind_check;
ALTER TABLE artifact_refs ADD CONSTRAINT artifact_refs_kind_check
    CHECK (kind IN ('MANIFEST', 'SNAPSHOT', 'TRACE', 'REPORT', 'CHAIN'));

ALTER TABLE replay_checkpoints
    ADD COLUMN IF NOT EXISTS restore_context TEXT,
    ADD COLUMN IF NOT EXISTS chain_artifact_checksum VARCHAR(71);
