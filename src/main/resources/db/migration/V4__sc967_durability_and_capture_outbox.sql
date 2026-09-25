-- SC-967: durable capture outbox, assistant generation leases, candidate bundle evidence.
--
-- Idempotent additive migration:
--   * routine_capture_records gains an optimistic @Version column plus a leased operation slot
--     (kind, state, immutable request hash, external correlation id, attempt count) so capture
--     start/complete phases commit durable work before and after external recording I/O;
--   * routine_assistant_conversations persists the generation message and attempt count and gets
--     an index driving the expired-lease recovery scanner;
--   * patch_candidate_runs persists the exact manifest file hash and component hashes/sizes so
--     every registration action can revalidate the persisted bundle byte-for-byte.

ALTER TABLE routine_capture_records ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE routine_capture_records ADD COLUMN IF NOT EXISTS operation VARCHAR(30);
ALTER TABLE routine_capture_records ADD COLUMN IF NOT EXISTS operation_state VARCHAR(20);
ALTER TABLE routine_capture_records ADD COLUMN IF NOT EXISTS operation_request_hash VARCHAR(71);
ALTER TABLE routine_capture_records ADD COLUMN IF NOT EXISTS operation_correlation_id VARCHAR(160);
ALTER TABLE routine_capture_records ADD COLUMN IF NOT EXISTS operation_attempts INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_routine_capture_operation_state
    ON routine_capture_records (operation_state);

ALTER TABLE routine_assistant_conversations ADD COLUMN IF NOT EXISTS generation_message TEXT;
ALTER TABLE routine_assistant_conversations ADD COLUMN IF NOT EXISTS generation_attempts INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_routine_assistant_generation_lease
    ON routine_assistant_conversations (generation_state, generation_lease_expires_at);

ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS manifest_file_hash VARCHAR(71);
ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS jar_sha256 VARCHAR(71);
ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS jar_size BIGINT;
ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS sbom_sha256 VARCHAR(71);
ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS sbom_size BIGINT;
ALTER TABLE patch_candidate_runs ADD COLUMN IF NOT EXISTS certification_document_hash VARCHAR(71);
