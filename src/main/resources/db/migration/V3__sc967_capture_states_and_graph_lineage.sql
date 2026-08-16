-- SC-967: durable capture states and graph lineage/supersession.
--
-- Idempotent additive migration for gap-closure:
--   * routine_capture_records.completion_status/error let capture start and completion be
--     durable (STARTING/RECORDING/COMPLETING/COMPLETED/UNCERTAIN) so a retry with the same
--     idempotency key reconciles the same pending record instead of starting a second recording;
--   * behavior_graph_definitions.supersedes_graph_id records the explicit assistant-graph
--     supersession edge so an accepted proposal creates a valid successor graph without
--     overwriting the immutable run graph identity.

ALTER TABLE routine_capture_records ADD COLUMN IF NOT EXISTS completion_status VARCHAR(20) NOT NULL DEFAULT 'STARTING';
ALTER TABLE routine_capture_records ADD COLUMN IF NOT EXISTS completion_error VARCHAR(500);

ALTER TABLE behavior_graph_definitions ADD COLUMN IF NOT EXISTS supersedes_graph_id VARCHAR(160);
