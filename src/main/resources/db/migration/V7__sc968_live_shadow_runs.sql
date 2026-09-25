CREATE TABLE routine_capture_batches (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(160) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    sequence INTEGER NOT NULL,
    values_json TEXT NOT NULL,
    accepted_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_routine_capture_batch_source UNIQUE (session_id, kind, sequence)
);

CREATE INDEX idx_routine_capture_batch_session ON routine_capture_batches(session_id, accepted_at);

CREATE TABLE routine_shadow_runs (
    id BIGSERIAL PRIMARY KEY,
    shadow_run_id VARCHAR(180) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    model_identity_json TEXT NOT NULL,
    session_identity_json TEXT NOT NULL,
    state VARCHAR(20) NOT NULL,
    next_decision_ordinal INTEGER NOT NULL DEFAULT 1,
    graph_cursor INTEGER NOT NULL DEFAULT 0,
    last_processed_tick BIGINT,
    optimistic_version BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    result_json TEXT,
    result_hash VARCHAR(71),
    failure_code VARCHAR(120),
    failure_message VARCHAR(500),
    CONSTRAINT uq_routine_shadow_run_id UNIQUE (shadow_run_id),
    CONSTRAINT uq_routine_shadow_model_session UNIQUE (model_id, session_id)
);

CREATE INDEX idx_routine_shadow_runs_session_state ON routine_shadow_runs(session_id, state);

CREATE TABLE routine_shadow_decisions (
    id BIGSERIAL PRIMARY KEY,
    shadow_run_id VARCHAR(180) NOT NULL REFERENCES routine_shadow_runs(shadow_run_id),
    decision_ordinal INTEGER NOT NULL,
    source_event_kind VARCHAR(20) NOT NULL,
    source_batch_sequence INTEGER NOT NULL,
    source_event_index INTEGER NOT NULL,
    tick BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    recommendation_json TEXT,
    observed_action_json TEXT,
    outcome VARCHAR(20) NOT NULL,
    model_identity_hash VARCHAR(71) NOT NULL,
    session_identity_hash VARCHAR(71) NOT NULL,
    decision_content_hash VARCHAR(71) NOT NULL,
    control_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_routine_shadow_decision_ordinal UNIQUE (shadow_run_id, decision_ordinal),
    CONSTRAINT uq_routine_shadow_decision_source UNIQUE (shadow_run_id, source_event_kind, source_batch_sequence, source_event_index),
    CONSTRAINT ck_routine_shadow_control CHECK (control_allowed = FALSE)
);

CREATE INDEX idx_routine_shadow_decisions_run_tick ON routine_shadow_decisions(shadow_run_id, tick);
