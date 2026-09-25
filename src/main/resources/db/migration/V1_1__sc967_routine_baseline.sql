-- SC-967: Flyway baseline for every AI-routine JPA table.
--
-- Fresh databases bootstrap entirely through Flyway (Flyway runs before Hibernate schema
-- generation): V2/V3 alter these tables, so they must already exist when V2 is reached. On
-- databases where Hibernate previously created the tables, every statement is a no-op because of
-- IF NOT EXISTS; those histories already applied V2/V3 successfully and therefore already have the
-- altered tables. spring.flyway.out-of-order=true lets this older versioned baseline be adopted by
-- existing histories without a manual baseline insert.
--
-- Column shapes mirror the JPA mappings (@Lob String -> TEXT per V2 precedent); unique indexes are
-- owned here and never relied on from Hibernate ddl-auto update.

CREATE TABLE IF NOT EXISTS current_slice_runs (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(160) NOT NULL,
    attempt_id VARCHAR(160),
    identity_json TEXT NOT NULL,
    stage VARCHAR(40) NOT NULL,
    control_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_current_slice_runs_run_id ON current_slice_runs (run_id);

CREATE TABLE IF NOT EXISTS routine_capture_records (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    session_id VARCHAR(160) NOT NULL,
    start_idempotency_key VARCHAR(240) NOT NULL,
    start_request_hash VARCHAR(71) NOT NULL,
    identity_json TEXT NOT NULL,
    started_at TIMESTAMP NOT NULL,
    start_tick BIGINT NOT NULL,
    ticks_json TEXT NOT NULL,
    actions_json TEXT NOT NULL,
    tick_sequences_json TEXT NOT NULL,
    action_sequences_json TEXT NOT NULL,
    tick_idempotency_json TEXT NOT NULL,
    action_idempotency_json TEXT NOT NULL,
    request_idempotency_json TEXT NOT NULL,
    next_tick_sequence INTEGER NOT NULL DEFAULT 1,
    next_action_sequence INTEGER NOT NULL DEFAULT 1,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    final_tick INTEGER,
    recording_id VARCHAR(160),
    recording_hash VARCHAR(71),
    video_sha256 VARCHAR(71),
    completion_request_hash VARCHAR(71),
    debug_session_id VARCHAR(160),
    video_path VARCHAR(500),
    completion_status VARCHAR(20) NOT NULL DEFAULT 'STARTING',
    completion_error VARCHAR(500),
    operation VARCHAR(30),
    operation_state VARCHAR(20),
    operation_request_hash VARCHAR(71),
    operation_correlation_id VARCHAR(160),
    operation_attempts INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_capture_session ON routine_capture_records (session_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_capture_recording ON routine_capture_records (recording_id);

CREATE TABLE IF NOT EXISTS routine_curations (
    id BIGSERIAL PRIMARY KEY,
    recording_id VARCHAR(160) NOT NULL,
    include_ranges_json TEXT,
    exclude_ranges_json TEXT,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_curations_recording ON routine_curations (recording_id);

CREATE TABLE IF NOT EXISTS behavior_graph_definitions (
    id BIGSERIAL PRIMARY KEY,
    graph_id VARCHAR(160) NOT NULL,
    graph_hash VARCHAR(71) NOT NULL,
    identity_json TEXT NOT NULL,
    definition_json TEXT NOT NULL,
    assistant_diff_json TEXT,
    supersedes_graph_id VARCHAR(160),
    state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_behavior_graph_definitions_graph_id ON behavior_graph_definitions (graph_id);

CREATE TABLE IF NOT EXISTS routine_assistant_conversations (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    conversation_id VARCHAR(160) NOT NULL,
    run_id VARCHAR(160) NOT NULL,
    state VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    next_event_id BIGINT NOT NULL DEFAULT 0,
    generation_token VARCHAR(80),
    generation_state VARCHAR(20),
    generation_lease_expires_at TIMESTAMP,
    generation_message TEXT,
    generation_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_assistant_conversations_conversation_id
    ON routine_assistant_conversations (conversation_id);

CREATE TABLE IF NOT EXISTS routine_assistant_events (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(160) NOT NULL,
    event_id BIGINT NOT NULL,
    type VARCHAR(60) NOT NULL,
    data_json TEXT,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_assistant_events_cursor
    ON routine_assistant_events (conversation_id, event_id);

CREATE TABLE IF NOT EXISTS routine_assistant_proposals (
    id BIGSERIAL PRIMARY KEY,
    proposal_id VARCHAR(160) NOT NULL,
    conversation_id VARCHAR(160) NOT NULL,
    diff_json TEXT,
    state VARCHAR(30) NOT NULL DEFAULT 'PROPOSED',
    decision_reason VARCHAR(255),
    decided_at TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_assistant_proposals_proposal_id
    ON routine_assistant_proposals (proposal_id);

CREATE TABLE IF NOT EXISTS routine_models (
    id BIGSERIAL PRIMARY KEY,
    model_id VARCHAR(160) NOT NULL,
    graph_id VARCHAR(160) NOT NULL,
    model_hash VARCHAR(71) NOT NULL,
    identity_json TEXT NOT NULL,
    definition_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_models_model_id ON routine_models (model_id);

CREATE TABLE IF NOT EXISTS routine_replay_traces (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(180) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    recording_id VARCHAR(160) NOT NULL,
    trace_mode VARCHAR(30) NOT NULL,
    content_hash VARCHAR(71) NOT NULL,
    identity_json TEXT NOT NULL,
    result_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_routine_replay_traces_trace_id ON routine_replay_traces (trace_id);

CREATE TABLE IF NOT EXISTS patch_candidate_runs (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    proposal_id VARCHAR(160) NOT NULL,
    state VARCHAR(40) NOT NULL,
    failure_manifest_hash VARCHAR(71) NOT NULL,
    source_snapshot_id VARCHAR(40) NOT NULL,
    source_manifest_hash VARCHAR(71) NOT NULL,
    source_manifest_json TEXT NOT NULL,
    source_file_id VARCHAR(80) NOT NULL,
    source_relative_path VARCHAR(500) NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    preimage_hash VARCHAR(71) NOT NULL,
    base_commit VARCHAR(160) NOT NULL,
    patch_text TEXT NOT NULL,
    binding_json TEXT NOT NULL,
    identity_json TEXT,
    patch_hash VARCHAR(71) NOT NULL,
    proposal_hash VARCHAR(71) NOT NULL,
    approver_id VARCHAR(255),
    approved_at TIMESTAMP,
    approval_hash VARCHAR(71),
    candidate_id VARCHAR(40),
    candidate_hash VARCHAR(71),
    candidate_manifest_json TEXT,
    final_identity_json TEXT,
    result_json TEXT,
    worker_started_at TIMESTAMP,
    worker_completed_at TIMESTAMP,
    candidate_staging_directory VARCHAR(500),
    registration_request_hash VARCHAR(71),
    registry_response_json TEXT,
    last_failure_code VARCHAR(160),
    worker_attempt_id VARCHAR(80),
    worker_lease_expires_at TIMESTAMP,
    registration_attempt_id VARCHAR(80),
    registration_prepared_at TIMESTAMP,
    registry_reconciled_at TIMESTAMP,
    manifest_file_hash VARCHAR(71),
    jar_sha256 VARCHAR(71),
    jar_size BIGINT,
    sbom_sha256 VARCHAR(71),
    sbom_size BIGINT,
    certification_document_hash VARCHAR(71),
    updated_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_patch_candidate_runs_proposal_id ON patch_candidate_runs (proposal_id);
