-- SC-967: durable, resumable candidate build/registration evidence.
--
-- Idempotent additive migration for the durable candidate workflow:
--   * worker lease/attempt fields let a bounded worker claim BUILD_QUEUED records and recover
--     expired leases after restart;
--   * registration attempt/prepared-at fields make REGISTERING reconciliation recoverable from the
--     exact persisted staging bundle instead of ephemeral temp state.

ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS worker_attempt_id VARCHAR(80);
ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS worker_lease_expires_at TIMESTAMP;
ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS registration_attempt_id VARCHAR(80);
ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS registration_prepared_at TIMESTAMP;
ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS registry_reconciled_at TIMESTAMP;

-- SC-967: assistant diff metadata lives outside the schema-validated graph document.
ALTER TABLE behavior_graph_definitions ADD COLUMN IF NOT EXISTS assistant_diff_json TEXT;
