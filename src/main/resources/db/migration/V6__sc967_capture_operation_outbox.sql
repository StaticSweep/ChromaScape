-- SC-967: durable capture operations and final registration evidence.
--
-- V1 through V5 are released and intentionally remain immutable.  Capture work is represented
-- by its own immutable operation row; the capture row is only the materialized session state.
-- Recorder I/O is therefore never required for the request transaction to commit.

CREATE TABLE IF NOT EXISTS routine_capture_operations (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(160) NOT NULL,
    operation VARCHAR(20) NOT NULL,
    request_hash VARCHAR(71) NOT NULL,
    request_json TEXT NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_id VARCHAR(80),
    lease_expires_at TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result_json TEXT,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT routine_capture_operations_kind_ck CHECK (operation IN ('START', 'COMPLETE')),
    CONSTRAINT routine_capture_operations_state_ck CHECK
      (state IN ('PENDING', 'LEASED', 'SUCCEEDED', 'UNCERTAIN', 'FAILED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_capture_operations_session_kind
    ON routine_capture_operations (session_id, operation);
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_capture_operations_correlation
    ON routine_capture_operations (correlation_id);
CREATE INDEX IF NOT EXISTS idx_routine_capture_operations_claim
    ON routine_capture_operations (state, next_attempt_at, lease_expires_at, created_at);
CREATE INDEX IF NOT EXISTS idx_routine_capture_operations_session
    ON routine_capture_operations (session_id, operation, state);

CREATE TABLE IF NOT EXISTS routine_capture_external_bindings (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    correlation_id VARCHAR(160) NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    debug_session_id VARCHAR(160) NOT NULL,
    recording_id VARCHAR(160),
    start_attempted BOOLEAN NOT NULL DEFAULT FALSE,
    completion_attempted BOOLEAN NOT NULL DEFAULT FALSE,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_capture_external_binding_correlation
    ON routine_capture_external_bindings (correlation_id);
CREATE INDEX IF NOT EXISTS idx_routine_capture_external_binding_session
    ON routine_capture_external_bindings (session_id, debug_session_id);

-- Adopt unfinished V3/V4 rows without fabricating an external result. The legacy operation
-- columns remain read-only compatibility data; all new dispatching uses this table.
WITH legacy AS (
    SELECT r.session_id,
           'START'::VARCHAR AS operation,
           COALESCE(r.operation_request_hash, r.start_request_hash,
                    'legacy-request-' || md5(r.session_id)) AS request_hash,
           json_build_object('endpoint', '/capture/sessions', 'session_id', r.session_id,
                             'request_hash', r.start_request_hash,
                             'task_key', COALESCE(
                                 -- ::text first: the cast is valid whether the baseline created
                                 -- this column as TEXT or Hibernate had already created it as an
                                 -- oid large object before Flyway was adopted.
                                 (r.identity_json::text::jsonb #>> '{task,task_key}'),
                                 'FISHING_TRAINING'))::text AS request_json,
           COALESCE(NULLIF(r.operation_correlation_id, ''),
                    'legacy-start-' || md5(r.session_id)) AS raw_correlation_id,
           COALESCE(r.operation_attempts, 0) AS attempts
    FROM routine_capture_records r
    WHERE r.completion_status IN ('STARTING', 'UNCERTAIN')
      AND (r.recording_id IS NULL OR btrim(r.recording_id) = '')
      AND NOT EXISTS (
          SELECT 1 FROM routine_capture_operations o
          WHERE o.session_id = r.session_id AND o.operation = 'START'
      )
    UNION ALL
    SELECT r.session_id,
           'COMPLETE'::VARCHAR,
           r.completion_request_hash,
           json_build_object('endpoint', '/capture/sessions/' || r.session_id || '/complete',
                             'session_id', r.session_id,
                             'request_hash', r.completion_request_hash,
                             'final_tick', COALESCE(r.final_tick, 0))::text,
           COALESCE(NULLIF(r.operation_correlation_id, ''),
                    'legacy-complete-' || md5(r.session_id)),
           COALESCE(r.operation_attempts, 0)
    FROM routine_capture_records r
    WHERE r.completion_status IN ('COMPLETING', 'UNCERTAIN')
      AND r.recording_id IS NOT NULL
      AND btrim(r.recording_id) <> ''
      AND r.completion_request_hash IS NOT NULL
      AND btrim(r.completion_request_hash) <> ''
      AND NOT EXISTS (
          SELECT 1 FROM routine_capture_operations o
          WHERE o.session_id = r.session_id AND o.operation = 'COMPLETE'
      )
), ranked AS (
    SELECT legacy.*,
           row_number() OVER (
               PARTITION BY raw_correlation_id
               ORDER BY session_id, operation
           ) AS correlation_rank
    FROM legacy
)
INSERT INTO routine_capture_operations
    (session_id, operation, request_hash, request_json, correlation_id, state,
     attempts, next_attempt_at, created_at, updated_at)
SELECT r.session_id,
       r.operation,
       r.request_hash,
       r.request_json,
       CASE
         WHEN r.correlation_rank = 1
              AND NOT EXISTS (
                  SELECT 1 FROM routine_capture_operations existing
                  WHERE existing.correlation_id = r.raw_correlation_id
              )
           THEN r.raw_correlation_id
         ELSE 'legacy-' || lower(r.operation) || '-' ||
              md5(r.raw_correlation_id || ':' || r.session_id || ':' || r.operation)
       END,
       'PENDING',
       r.attempts,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM ranked r;

ALTER TABLE routine_assistant_proposals
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE routine_assistant_proposals
    ADD COLUMN IF NOT EXISTS draft_graph_id VARCHAR(160);
ALTER TABLE routine_assistant_proposals
    ADD COLUMN IF NOT EXISTS accepted_event_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_assistant_proposals_draft_graph
    ON routine_assistant_proposals (draft_graph_id)
    WHERE draft_graph_id IS NOT NULL;

ALTER TABLE patch_candidate_runs
    ADD COLUMN IF NOT EXISTS registry_response_hash VARCHAR(71);
ALTER TABLE patch_candidate_runs
    ADD COLUMN IF NOT EXISTS registry_response_bytes BYTEA;
ALTER TABLE patch_candidate_runs
    ADD COLUMN IF NOT EXISTS registry_manifest_hash VARCHAR(71);
ALTER TABLE patch_candidate_runs
    ADD COLUMN IF NOT EXISTS registry_download_hash VARCHAR(71);
ALTER TABLE patch_candidate_runs
    ADD COLUMN IF NOT EXISTS registry_verified_at TIMESTAMP;
ALTER TABLE patch_candidate_runs
    ADD COLUMN IF NOT EXISTS registration_lease_expires_at TIMESTAMP;
-- Drifted databases where Hibernate created patch_candidate_runs before the Flyway baseline
-- (CREATE TABLE IF NOT EXISTS no-op) may lack the baseline's updated_at column.
ALTER TABLE patch_candidate_runs
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_patch_candidate_registration_claim
    ON patch_candidate_runs (state, registration_lease_expires_at, updated_at);
