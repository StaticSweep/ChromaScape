-- SC-665 clean current JPA baseline; canonical SC-1430/1431 DDL remains authoritative.
CREATE TABLE gameplay_provider_session (
    session_id VARCHAR(160) PRIMARY KEY,
    provider VARCHAR(16) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    state VARCHAR(32) NOT NULL,
    task_id VARCHAR(160) NOT NULL,
    run_id VARCHAR(160) NOT NULL,
    recording_id VARCHAR(160) NOT NULL,
    prompt_digest VARCHAR(71) NOT NULL,
    toolset_digest VARCHAR(71) NOT NULL,
    knowledge_digest VARCHAR(71) NOT NULL,
    goal_plan_digest VARCHAR(71) NOT NULL,
    request_json TEXT NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    decide_only BOOLEAN NOT NULL CHECK (decide_only),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE gameplay_provider_budget_ledger (
    session_id VARCHAR(160) PRIMARY KEY REFERENCES gameplay_provider_session(session_id),
    input_tokens_used BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens_used >= 0),
    output_tokens_used BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens_used >= 0),
    provider_calls_used INTEGER NOT NULL DEFAULT 0 CHECK (provider_calls_used >= 0),
    tool_calls_used INTEGER NOT NULL DEFAULT 0 CHECK (tool_calls_used >= 0),
    turns_used INTEGER NOT NULL DEFAULT 0 CHECK (turns_used >= 0),
    elapsed_millis BIGINT NOT NULL DEFAULT 0 CHECK (elapsed_millis >= 0),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE gameplay_provider_turn (
    turn_id VARCHAR(160) PRIMARY KEY,
    session_id VARCHAR(160) NOT NULL REFERENCES gameplay_provider_session(session_id),
    request_id VARCHAR(160) NOT NULL UNIQUE,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(71) NOT NULL,
    state VARCHAR(32) NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    provider_session_ref VARCHAR(160),
    provider_turn_ref VARCHAR(160),
    observation_digest VARCHAR(71) NOT NULL,
    eligibility_digest VARCHAR(71) NOT NULL,
    decision_digest VARCHAR(71),
    semantic_action_digest VARCHAR(71),
    request_json TEXT NOT NULL,
    usage_json TEXT,
    transcript_ref VARCHAR(160),
    deadline TIMESTAMPTZ NOT NULL,
    decide_only BOOLEAN NOT NULL CHECK (decide_only),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_gameplay_provider_turn_session ON gameplay_provider_turn(session_id, created_at);
CREATE INDEX idx_gameplay_provider_turn_recovery ON gameplay_provider_turn(state, updated_at);

CREATE TABLE gameplay_provider_event (
    event_id VARCHAR(160) PRIMARY KEY,
    turn_id VARCHAR(160) NOT NULL REFERENCES gameplay_provider_turn(turn_id),
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_digest VARCHAR(71) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (turn_id, sequence_no)
);

CREATE TABLE gameplay_provider_tool_call (
    tool_call_id VARCHAR(160) PRIMARY KEY,
    turn_id VARCHAR(160) NOT NULL REFERENCES gameplay_provider_turn(turn_id),
    tool_name VARCHAR(128) NOT NULL,
    arguments_digest VARCHAR(71) NOT NULL,
    result_digest VARCHAR(71),
    state VARCHAR(32) NOT NULL,
    charged_calls INTEGER NOT NULL CHECK (charged_calls >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE gameplay_provider_decision (
    decision_id VARCHAR(160) PRIMARY KEY,
    turn_id VARCHAR(160) NOT NULL UNIQUE REFERENCES gameplay_provider_turn(turn_id),
    contract_version VARCHAR(32) NOT NULL,
    decision_digest VARCHAR(71) NOT NULL,
    semantic_action_digest VARCHAR(71) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    decision_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE gameplay_provider_transcript_artifact (
    artifact_id VARCHAR(160) PRIMARY KEY,
    turn_id VARCHAR(160) NOT NULL UNIQUE REFERENCES gameplay_provider_turn(turn_id),
    sha256 VARCHAR(71) NOT NULL,
    redaction_version VARCHAR(32) NOT NULL,
    byte_length BIGINT NOT NULL CHECK (byte_length >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

-- SC-1431.1: durable normalized AccountBuilder orchestration evidence.
-- This migration is intentionally amended before V23 is shipped. No authority signature, key,
-- token secret, raw fence token or unredacted request payload is stored in these tables.

CREATE TABLE ab_orchestration (
    orchestration_id VARCHAR(128) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(80) NOT NULL,
    envelope_digest VARCHAR(80) NOT NULL,
    source_type VARCHAR(128) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    request_json TEXT NOT NULL,
    view_json TEXT NOT NULL,
    retry_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    view_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    entity_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ab_orchestration_mode_check
        CHECK (mode IN ('HELD', 'SHADOW', 'BOOTSTRAP_RECORDING', 'LIVE_TASK', 'SIMULATION')),
    CONSTRAINT ab_orchestration_state_check
        CHECK (state IN (
            'CREATED', 'VALIDATED', 'HELD', 'AUTHORIZED', 'RESERVED',
            'DISPATCH_SUBMITTED', 'ACTIVE', 'VERIFYING', 'CONFIRMED', 'REFUTED',
            'UNVERIFIABLE', 'DENIED', 'STOPPED', 'FAILED', 'NEEDS_RECONCILIATION')),
    CONSTRAINT ab_orchestration_fingerprint_check
        CHECK (request_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ab_orchestration_envelope_digest_check
        CHECK (envelope_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ab_orchestration_version_check
        CHECK (view_version >= 0)
);

CREATE TABLE ab_snapshot_binding (
    orchestration_id VARCHAR(128) PRIMARY KEY
        REFERENCES ab_orchestration(orchestration_id) ON DELETE RESTRICT,
    decision_digest VARCHAR(80) NOT NULL,
    semantic_action_digest VARCHAR(80) NOT NULL,
    catalog_digest VARCHAR(80) NOT NULL,
    capability_digest VARCHAR(80) NOT NULL,
    goal_plan_digest VARCHAR(80) NOT NULL,
    task_skill_digests TEXT NOT NULL,
    tool_schema_digest VARCHAR(80) NOT NULL,
    knowledge_digest VARCHAR(80) NOT NULL,
    policy_digest VARCHAR(80) NOT NULL,
    verifier_digest VARCHAR(80) NOT NULL,
    build_id VARCHAR(128) NOT NULL,
    expected_observation_revision BIGINT NOT NULL,
    expected_task_revision BIGINT NOT NULL,
    CONSTRAINT ab_binding_digest_check CHECK (
        decision_digest ~ '^sha256:[0-9a-f]{64}$'
        AND semantic_action_digest ~ '^sha256:[0-9a-f]{64}$'
        AND catalog_digest ~ '^sha256:[0-9a-f]{64}$'
        AND capability_digest ~ '^sha256:[0-9a-f]{64}$'
        AND goal_plan_digest ~ '^sha256:[0-9a-f]{64}$'
        AND tool_schema_digest ~ '^sha256:[0-9a-f]{64}$'
        AND knowledge_digest ~ '^sha256:[0-9a-f]{64}$'
        AND policy_digest ~ '^sha256:[0-9a-f]{64}$'
        AND verifier_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE ab_authority_consumption (
    authority_id VARCHAR(128) PRIMARY KEY,
    orchestration_id VARCHAR(128) NOT NULL UNIQUE
        REFERENCES ab_orchestration(orchestration_id) ON DELETE RESTRICT,
    envelope_digest VARCHAR(80) NOT NULL,
    approved_scope_digest VARCHAR(80) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ab_authority_envelope_digest_check
        CHECK (envelope_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ab_authority_scope_digest_check
        CHECK (approved_scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ab_authority_operator_check
        CHECK (operator_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ab_authority_expiry_check
        CHECK (expires_at > consumed_at)
);

CREATE TABLE ab_orchestration_lease (
    orchestration_id VARCHAR(128) PRIMARY KEY
        REFERENCES ab_orchestration(orchestration_id) ON DELETE RESTRICT,
    reservation_id VARCHAR(128) NOT NULL UNIQUE,
    generation BIGINT NOT NULL,
    fence BIGINT NOT NULL,
    fence_token_digest VARCHAR(80) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(32) NOT NULL,
    entity_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ab_lease_generation_check CHECK (generation >= 1),
    CONSTRAINT ab_lease_fence_check CHECK (fence >= 1),
    CONSTRAINT ab_lease_digest_check CHECK (fence_token_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ab_lease_state_check CHECK (state IN (
        'RESERVED', 'DISPATCH_SUBMITTED', 'ACTIVE', 'VERIFYING', 'CONFIRMED',
        'REFUTED', 'UNVERIFIABLE', 'STOPPED', 'FAILED', 'NEEDS_RECONCILIATION'))
);

CREATE TABLE ab_orchestration_event (
    event_id VARCHAR(64) PRIMARY KEY,
    orchestration_id VARCHAR(128) NOT NULL
        REFERENCES ab_orchestration(orchestration_id) ON DELETE RESTRICT,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_digest VARCHAR(80) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ab_event_sequence_unique UNIQUE (orchestration_id, sequence_number),
    CONSTRAINT ab_event_sequence_check CHECK (sequence_number >= 1),
    CONSTRAINT ab_event_digest_check CHECK (payload_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE TABLE ab_orchestration_receipt (
    receipt_id VARCHAR(128) PRIMARY KEY,
    orchestration_id VARCHAR(128) NOT NULL
        REFERENCES ab_orchestration(orchestration_id) ON DELETE RESTRICT,
    receipt_identity VARCHAR(256) NOT NULL,
    receipt_digest VARCHAR(80) NOT NULL,
    sequence_number BIGINT NOT NULL,
    phase VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    stable_code VARCHAR(64) NOT NULL,
    receipt_json TEXT NOT NULL,
    evidence_digest VARCHAR(80),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ab_receipt_identity_unique UNIQUE (orchestration_id, receipt_identity),
    CONSTRAINT ab_receipt_digest_unique UNIQUE (orchestration_id, phase, receipt_digest),
    CONSTRAINT ab_receipt_sequence_unique UNIQUE (orchestration_id, sequence_number),
    CONSTRAINT ab_receipt_sequence_check CHECK (sequence_number >= 1),
    CONSTRAINT ab_receipt_digest_check CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ab_receipt_evidence_digest_check CHECK (
        evidence_digest IS NULL OR evidence_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE ab_orchestration_terminal_result (
    orchestration_id VARCHAR(128) PRIMARY KEY
        REFERENCES ab_orchestration(orchestration_id) ON DELETE RESTRICT,
    outcome VARCHAR(32) NOT NULL,
    stable_code VARCHAR(64) NOT NULL,
    retry_allowed BOOLEAN NOT NULL,
    reconciled BOOLEAN NOT NULL,
    evidence_digest VARCHAR(80),
    terminal_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ab_terminal_evidence_digest_check CHECK (
        evidence_digest IS NULL OR evidence_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE INDEX ab_orchestration_state_idx ON ab_orchestration(state);
CREATE INDEX ab_orchestration_updated_idx ON ab_orchestration(updated_at);
CREATE INDEX ab_event_orchestration_idx
    ON ab_orchestration_event(orchestration_id, sequence_number);
CREATE INDEX ab_receipt_orchestration_idx
    ON ab_orchestration_receipt(orchestration_id, sequence_number);

-- The application performs all writes through locked transactions, but the evidence tables must
-- remain append-only even if a maintenance connection bypasses the JPA store.
CREATE OR REPLACE FUNCTION ab_reject_evidence_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'SC-1431 evidence table % is append-only', TG_TABLE_NAME
        USING ERRCODE = '55006';
    RETURN NULL;
END;
$$;

CREATE TRIGGER ab_snapshot_binding_append_only
    BEFORE UPDATE OR DELETE ON ab_snapshot_binding
    FOR EACH ROW EXECUTE FUNCTION ab_reject_evidence_mutation();

CREATE TRIGGER ab_authority_consumption_append_only
    BEFORE UPDATE OR DELETE ON ab_authority_consumption
    FOR EACH ROW EXECUTE FUNCTION ab_reject_evidence_mutation();

CREATE TRIGGER ab_orchestration_event_append_only
    BEFORE UPDATE OR DELETE ON ab_orchestration_event
    FOR EACH ROW EXECUTE FUNCTION ab_reject_evidence_mutation();

CREATE TRIGGER ab_orchestration_receipt_append_only
    BEFORE UPDATE OR DELETE ON ab_orchestration_receipt
    FOR EACH ROW EXECUTE FUNCTION ab_reject_evidence_mutation();

CREATE TRIGGER ab_terminal_result_write_once
    BEFORE UPDATE OR DELETE ON ab_orchestration_terminal_result
    FOR EACH ROW EXECUTE FUNCTION ab_reject_evidence_mutation();

CREATE OR REPLACE FUNCTION ab_lease_identity_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       OR NEW.reservation_id IS DISTINCT FROM OLD.reservation_id
       OR NEW.generation IS DISTINCT FROM OLD.generation
       OR NEW.fence IS DISTINCT FROM OLD.fence
       OR NEW.fence_token_digest IS DISTINCT FROM OLD.fence_token_digest
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'SC-1431 lease identity is immutable'
            USING ERRCODE = '55006';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ab_lease_identity_immutable
    BEFORE UPDATE OR DELETE ON ab_orchestration_lease
    FOR EACH ROW EXECUTE FUNCTION ab_lease_identity_guard();


-- SC-665 clean baseline generated from committed df6eff61 JPA mappings.
-- Reviewed current schema;
create table artifact_refs (id uuid not null, checksum varchar(255) not null, created_at timestamp(6) with time zone not null, finalized_at timestamp(6) with time zone, kind varchar(30) not null check (kind in ('MANIFEST','SNAPSHOT','TRACE','REPORT')), media_type varchar(100), run_id uuid not null, size_bytes bigint not null, state varchar(20) not null, primary key (id));
create table behavior_graph_definitions (id bigint generated by default as identity, assistant_diff_json text, created_at timestamp(6) with time zone not null, definition_json text not null, graph_hash varchar(71) not null, graph_id varchar(160) not null unique, identity_json text not null, state varchar(20) not null, supersedes_graph_id varchar(160), primary key (id));
create table curated_actions (curation_id uuid not null, sequence integer not null, tick bigint not null, arguments_json jsonb, confidence numeric(38,2), evidence_ref jsonb, semantic_action_type varchar(255), status varchar(255) not null, target_stable_id varchar(255), primary key (curation_id, sequence, tick));
create table current_slice_runs (id bigint generated by default as identity, attempt_id varchar(160), control_allowed boolean not null, created_at timestamp(6) with time zone not null, identity_json text not null, run_id varchar(160) not null unique, stage varchar(40) not null, primary key (id));
create table debug_sessions (id bigint generated by default as identity, account_name varchar(100), created_at timestamp(6) not null, end_tick bigint, end_time timestamp(6), failure_code varchar(50), failure_context varchar(2000), failure_reason varchar(500), last_state_at timestamp(6), lifecycle_state varchar(20) check (lifecycle_state in ('STARTING','RECORDING','FINALIZED','FAILED')), recording_id varchar(100), script_name varchar(100), session_id varchar(100) not null unique, start_tick bigint not null, start_time timestamp(6) not null, primary key (id));
create table decisions (id bigint generated by default as identity, decision_reason varchar(500), decision_type varchar(20) not null check (decision_type in ('ATTACK','MOVE','INTERACT','WAIT','BANK','CAMERA','OTHER')), failure_code varchar(50), failure_reason varchar(1000), game_state TEXT, recording_session_id bigint, screen_coords varchar(100), script_name varchar(100) not null, success boolean not null, target_entity varchar(500), timestamp timestamp(6) not null, primary key (id));
create table execution_runtime_snapshot (id bigint generated by default as identity, context_data_json TEXT, created_at timestamp(6) not null, execution_id varchar(100) not null unique, last_updated_at timestamp(6), runtime_data_json TEXT, snapshot_version integer, primary key (id));
create table field_provenance (json_pointer varchar(500) not null, derivation_version varchar(50), evidence_ref jsonb, provenance varchar(30) not null check (provenance in ('OBSERVED','DERIVED','OPERATOR_SUPPLIED','DEFAULTED')), reason varchar(500), supplied_by varchar(100), curation_id uuid not null, primary key (curation_id, json_pointer));
create table logs (id bigint generated by default as identity, debug_session_id varchar(100), level varchar(20) not null, log_type varchar(50), message TEXT not null, recording_id varchar(100), script_name varchar(100), source varchar(100), structured_data TEXT, tick integer, timestamp timestamp(6) not null, primary key (id));
create table patch_candidate_runs (id bigint generated by default as identity, approval_hash varchar(71), approved_at timestamp(6) with time zone, approver_id varchar(255), base_commit varchar(160) not null, binding_json text not null, candidate_hash varchar(71), candidate_id varchar(40), candidate_manifest_json text, candidate_staging_directory varchar(500), certification_document_hash varchar(71), end_line integer not null, failure_manifest_hash varchar(71) not null, final_identity_json text, identity_json text, jar_sha256 varchar(71), jar_size bigint, last_failure_code varchar(160), manifest_file_hash varchar(71), patch_hash varchar(71) not null, patch_text text not null, preimage_hash varchar(71) not null, proposal_hash varchar(71) not null, proposal_id varchar(160) not null unique, registration_attempt_id varchar(80), registration_lease_expires_at timestamp(6) with time zone, registration_prepared_at timestamp(6) with time zone, registration_request_hash varchar(71), registry_download_hash varchar(71), registry_lookup_bytes BYTEA, registry_lookup_hash varchar(71), registry_manifest_hash varchar(71), registry_reconciled_at timestamp(6) with time zone, registry_response_bytes BYTEA, registry_response_hash varchar(71), registry_response_json text, registry_verified_at timestamp(6) with time zone, result_json text, sbom_sha256 varchar(71), sbom_size bigint, source_file_id varchar(80) not null, source_manifest_hash varchar(71) not null, source_manifest_json text not null, source_relative_path varchar(500) not null, source_snapshot_id varchar(40) not null, start_line integer not null, state varchar(40) not null, updated_at timestamp(6) with time zone not null, version bigint, worker_attempt_id varchar(80), worker_completed_at timestamp(6) with time zone, worker_lease_expires_at timestamp(6) with time zone, worker_started_at timestamp(6) with time zone, primary key (id));
create table patch_loop_analysis_artifact (id bigint generated by default as identity, analysis_id varchar(100) unique, attempt_number integer, confidence varchar(50), diagnosis_summary TEXT, execution_id varchar(100) not null, recommendation_count integer, root_cause TEXT, timestamp timestamp(6) not null, primary key (id));
create table patch_loop_attempt (id bigint generated by default as identity, attempt_number integer not null, completed_at timestamp(6), execution_id varchar(100) not null, log_count integer, outcome varchar(50) check (outcome in ('RUNNING','SUCCESS','FAILURE','TIMEOUT','CRASH','CANCELLED','POLICY_VIOLATION')), run_recording_id varchar(100), run_session_id varchar(100), run_success boolean, started_at timestamp(6), stop_reason varchar(200), tick_count integer, primary key (id));
create table patch_loop_event (id bigint generated by default as identity, data jsonb, event_type varchar(50) not null check (event_type in ('ITERATION_CREATED','ITERATION_STARTED','ITERATION_COMPLETED','ITERATION_FAILED','SCRIPT_STARTED','SCRIPT_COMPLETED','SCRIPT_FAILED','SCRIPT_TIMEOUT','EVIDENCE_COLLECTION_STARTED','EVIDENCE_COLLECTED','EVIDENCE_INSUFFICIENT','EVIDENCE_COLLECTION_FAILED','ANALYSIS_STARTED','ANALYSIS_COMPLETED','ANALYSIS_FAILED','ANALYSIS_TIMEOUT','RETEST_STARTED','RETEST_COMPLETED','RETEST_FAILED','STATE_TRANSITION','RUN_PAUSED','RUN_RESUMED','RUN_STOP_REQUESTED','RUN_STOPPED','RUN_RISK_TRIGGERED','RUN_STATUS_DEGRADED','ERROR','WARNING')), iteration_id varchar(100) not null, message varchar(500) not null, severity varchar(10), timestamp timestamp(6) not null, primary key (id));
create table patch_loop_evidence_artifact (id bigint generated by default as identity, attempt_number integer, count integer, evidence_type varchar(100), execution_id varchar(100) not null, summary TEXT, timestamp timestamp(6) not null, primary key (id));
create table patch_loop_graph_execution (id bigint generated by default as identity, attempt_count integer, completed_at timestamp(6), created_at timestamp(6) not null, current_node_id varchar(100), execution_id varchar(100) not null unique, execution_status varchar(50) not null check (execution_status in ('CREATED','RUNNING','PAUSED','COMPLETED','FAILED','CANCELLED','TIMEOUT','POLICY_VIOLATION')), final_status varchar(50), graph_json jsonb, iteration_id varchar(100), mermaid_source TEXT, policy_json jsonb, stop_reason varchar(200), updated_at timestamp(6) not null, primary key (id));
create table patch_loop_graph_node (id bigint generated by default as identity, attempt_number integer, completed_at timestamp(6), duration_ms bigint, error_message TEXT, execution_id varchar(100) not null, node_id varchar(100) not null, node_type varchar(50) not null check (node_type in ('RUN_SCRIPT','COLLECT_EVIDENCE','ANALYZE','GENERATE_PATCH','APPLY_PATCH','VALIDATE','RETEST','EVALUATE_SUCCESS','END','START')), output_data jsonb, output_summary TEXT, retry_count integer, started_at timestamp(6), status varchar(50) not null check (status in ('PENDING','RUNNING','COMPLETED','FAILED','SKIPPED','TIMEOUT')), primary key (id));
create table patch_loop_iteration (id bigint generated by default as identity, analysis_id varchar(100), analysis_request_json jsonb, analysis_response_json jsonb, analysis_time_ms bigint, completed_at timestamp(6), confidence varchar(20), context TEXT, created_at timestamp(6) not null, current_attempt integer, diagnosis_summary TEXT, evidence_config jsonb, evidence_decision_count integer, evidence_end_time timestamp(6), evidence_log_count integer, evidence_start_time timestamp(6), evidence_tick_log_count integer, execution_id varchar(100), execution_mode varchar(50), failure_code varchar(100), failure_context TEXT, failure_reason varchar(500), graph_execution_id varchar(100), impacted_modules_count integer, imported_payload_json jsonb, iteration_id varchar(100) not null unique, last_state_at timestamp(6) not null, latest_event_at timestamp(6), max_attempts integer, micro6_patched_at timestamp(6), micro6_revision_id varchar(100), parent_iteration_id varchar(100), patch_draft_summary TEXT, patch_id varchar(100), patch_target varchar(50), policy_json jsonb, prompt TEXT not null, prompt_type varchar(20), recommendation_count integer, result_type varchar(50), retest_plan_summary TEXT, root_cause TEXT, run_end_time timestamp(6), run_recording_id varchar(100), run_session_id varchar(100), run_start_time timestamp(6), run_success boolean, script_name varchar(200) not null, session_context jsonb, source_type varchar(50), status varchar(50) not null check (status in ('QUEUED','RUNNING_SCRIPT','RUN_COMPLETED','RUN_FAILED','COLLECTING_EVIDENCE','EVIDENCE_COLLECTED','INSUFFICIENT_EVIDENCE','ANALYZING','ANALYZED','RETESTING','RETESTED','COMPLETED','FAILED','CANCELLED')), stop_reason varchar(200), success_criteria jsonb, task_item_id varchar(100), task_metadata_json jsonb, task_plan_id varchar(100), primary key (id));
create table patch_loop_patch_artifact (id bigint generated by default as identity, affected_files jsonb, applied_at timestamp(6), apply_status varchar(50) not null check (apply_status in ('STAGED','APPLIED','FAILED','ROLLED_BACK','SKIPPED')), attempt_number integer, confidence varchar(20), created_at timestamp(6) not null, diff_text TEXT not null, execution_id varchar(100) not null, files_changed integer, git_branch varchar(200), git_commit varchar(100), lines_changed integer, patch_id varchar(100) not null unique, rationale TEXT, rollback_reason TEXT, rolled_back boolean, validation_error TEXT, validation_output TEXT, validation_passed boolean, primary key (id));
create table patch_loop_retest_artifact (id bigint generated by default as identity, attempt_number integer, baseline_attempt_number integer, comparison_summary TEXT, execution_id varchar(100) not null, improvements TEXT, regressions TEXT, success boolean, timestamp timestamp(6) not null, primary key (id));
create table patch_loop_validation_artifact (id bigint generated by default as identity, attempt_number integer, duration_ms bigint, execution_id varchar(100) not null, exit_code integer, output TEXT, passed boolean, patch_id varchar(100), rollback_reason varchar(500), rolled_back boolean, timestamp timestamp(6) not null, primary key (id));
create table recording_curations (id uuid not null, content_checksum varchar(255) not null, content_json jsonb not null, created_at timestamp(6) not null, created_by varchar(255) not null, debug_session_id varchar(255) not null, published_at timestamp(6), recording_id varchar(255) not null, role varchar(255) not null check (role in ('TRAIN','VALIDATION','HELD_OUT')), schema_version integer not null, state varchar(255) not null check (state in ('DRAFT','PUBLISHED','RETIRED')), version integer not null, primary key (id));
create table recording_sessions (id bigint generated by default as identity, account_name varchar(100), duration_ms bigint, end_time timestamp(6), fps integer, frame_count bigint, recording_id varchar(100) not null unique, replay_name varchar(160), replay_tags_json TEXT, script_name varchar(100), start_time timestamp(6) not null, trigger_type varchar(20) not null check (trigger_type in ('MANUAL','AUTO_START','ERROR_EVENT','MICRO6_AUTO')), video_codec varchar(50), video_path varchar(500), primary key (id));
create table replay_attempts (id uuid not null, attempt_no integer not null, created_at timestamp(6) not null, fencing_generation integer not null, heartbeat_at timestamp(6), job_id varchar(255), lease_expires_at timestamp(6), lease_owner varchar(255), run_id uuid not null, started_at timestamp(6), state varchar(255) not null, terminal_at timestamp(6), worker_identity varchar(255), primary key (id));
create table replay_checkpoints (id uuid not null, action_index integer not null, attempt_id uuid not null, branch_key varchar(255) not null, chain_hash varchar(255) not null, created_at timestamp(6) with time zone not null, parent_checkpoint_id uuid, run_id uuid not null, snapshot_artifact_checksum varchar(71) not null, snapshot_checksum varchar(71) not null, state_hash varchar(255) not null, tick bigint not null, primary key (id), unique (run_id, branch_key, tick, action_index));
create table replay_events (ordinal bigint not null, run_id uuid not null, attempt_id uuid not null, category varchar(255) not null, created_at timestamp(6) not null, event_type varchar(255) not null, payload jsonb, sequence integer not null, tick bigint not null, primary key (ordinal, run_id));
create table replay_mismatches (id uuid not null, actual_value text, attempt_id uuid not null, cascade_count integer, causal_parent_id uuid, checkpoint_id uuid, created_at timestamp(6) with time zone not null, domain varchar(255), expected_value text, first_tick bigint, json_pointer varchar(255), level varchar(10) not null check (level in ('HARD','SOFT','INFO')), related_action_ordinal integer, rule_id varchar(255) not null, run_id uuid not null, sequence integer, primary key (id));
create table replay_recording_actions (id uuid not null, action_type varchar(64) not null, arguments_json jsonb not null, captured_at timestamp(6) not null, confidence numeric(4,3) not null, content_checksum varchar(71) not null, ingested_at timestamp(6) not null, recording_id varchar(100), schema_name varchar(64) not null, sequence integer not null, session_id varchar(100) not null, source varchar(64) not null, target_json jsonb, target_kind varchar(32), target_stable_id varchar(255), tick bigint not null, primary key (id), constraint uq_replay_recording_action_session_tick_sequence unique (session_id, tick, sequence));
create table replay_reports (id uuid not null, action_coverage_pct NUMERIC(5,2) not null, approximation_count integer not null, attempt_id uuid not null, cache_revision varchar(255), checkpoint_count integer not null, content_revision varchar(255), control_allowed boolean not null, conversion_quality varchar(32) not null, executed_action_count integer not null, fidelity varchar(32) not null, final_chain_hash varchar(255), final_state_hash varchar(255), finalized_at timestamp(6) with time zone not null, gap_count integer not null, manifest_checksum varchar(255) not null, manifest_version integer not null, mismatch_hard_count integer not null, mismatch_info_count integer not null, mismatch_soft_count integer not null, mode varchar(255) not null check (mode in ('OBSERVED_PLAYBACK','POLICY_REPLAY','CAUSAL_SIMULATION')), outcome varchar(30) not null check (outcome in ('PASSED','FAILED','CANCELLED','TIMED_OUT','INFRASTRUCTURE_FAILURE')), report_checksum varchar(255) not null unique, report_json jsonb, run_id uuid not null unique, started_at timestamp(6) with time zone, terminal_at timestamp(6) with time zone, terminal_reason varchar(255), viewer_trace_checksum varchar(71), viewer_trace_frame_count bigint, xrsps_adapter_version varchar(255), xrsps_build_commit varchar(255), primary key (id));
create table replay_runs (id uuid not null, action_coverage_pct NUMERIC(5,2) not null, approximation_count integer not null, branch_key varchar(255) not null, cache_revision varchar(255), content_revision varchar(255), control_allowed boolean not null, conversion_quality varchar(32) not null, created_at timestamp(6) not null, fidelity varchar(32) not null, finalization_reserved_at timestamp(6), gap_count integer not null, idempotency_key varchar(255) not null, manifest_checksum varchar(255) not null, manifest_id uuid not null, manifest_version integer not null, mode varchar(255) not null check (mode in ('OBSERVED_PLAYBACK','POLICY_REPLAY','CAUSAL_SIMULATION')), outcome varchar(255) check (outcome in ('PASSED','FAILED','CANCELLED','TIMED_OUT','INFRASTRUCTURE_FAILURE')), owner_id varchar(255) not null, parent_checkpoint_id uuid, parent_run_id uuid, phase varchar(255) not null check (phase in ('PREFLIGHT','LOAD_MANIFEST','START_KERNEL','RESET','EXECUTE','COMPARE','FINALIZE_ARTIFACTS','FINALIZE_REPORT')), request_fingerprint varchar(71), started_at timestamp(6), state varchar(255) not null check (state in ('QUEUED','CLAIMED','STARTING','RUNNING','CANCELLING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')), stop_policy varchar(255) not null check (stop_policy in ('STOP_ON_FIRST_HARD','COLLECT_UNTIL_CHECKPOINT','COLLECT_ALL','RUN_TO_END')), terminal_at timestamp(6), terminal_reason varchar(255), viewer_requested boolean not null, viewer_trace_checksum varchar(71), viewer_trace_frame_count bigint, xrsps_adapter_version varchar(255), xrsps_build_commit varchar(255), primary key (id));
create table routine_assistant_conversations (id bigint generated by default as identity, conversation_id varchar(160) not null unique, created_at timestamp(6) with time zone not null, generation_attempts integer not null, generation_lease_expires_at timestamp(6) with time zone, generation_message text, generation_state varchar(20), generation_token varchar(80), next_event_id bigint not null, run_id varchar(160) not null, state varchar(30) not null, version bigint, primary key (id));
create table routine_assistant_events (id bigint generated by default as identity, conversation_id varchar(160) not null, created_at timestamp(6) with time zone not null, data_json text, event_id bigint not null, type varchar(60) not null, primary key (id), constraint idx_routine_assistant_events_cursor unique (conversation_id, event_id));
create table routine_assistant_proposals (id bigint generated by default as identity, accepted_event_id bigint, conversation_id varchar(160) not null, decided_at timestamp(6) with time zone, decision_reason varchar(255), diff_json text, draft_graph_id varchar(160) unique, proposal_id varchar(160) not null unique, state varchar(30) not null, version bigint, primary key (id));
create table routine_capture_batches (id bigint generated by default as identity, accepted_at timestamp(6) with time zone not null, kind varchar(20) not null, sequence integer not null, session_id varchar(160) not null, values_json text not null, primary key (id), constraint idx_routine_capture_batch_source unique (session_id, kind, sequence));
create table routine_capture_external_bindings (id bigint generated by default as identity, completed boolean not null, completion_attempted boolean not null, correlation_id varchar(160) not null unique, debug_session_id varchar(160) not null, recording_id varchar(160), session_id varchar(160) not null, start_attempted boolean not null, updated_at timestamp(6) with time zone not null, version bigint, primary key (id));
create table routine_capture_operations (id bigint generated by default as identity, attempt_id varchar(80), attempts integer not null, correlation_id varchar(160) not null unique, created_at timestamp(6) with time zone not null, last_error varchar(1000), lease_expires_at timestamp(6) with time zone, next_attempt_at timestamp(6) with time zone not null, operation varchar(20) not null, request_hash varchar(71) not null, request_json TEXT not null, result_json TEXT, session_id varchar(160) not null, state varchar(20) not null, updated_at timestamp(6) with time zone not null, primary key (id));
create table routine_capture_records (id bigint generated by default as identity, action_idempotency_json text not null, action_sequences_json text not null, actions_json text not null, completed boolean not null, completion_error varchar(500), completion_request_hash varchar(71), completion_status varchar(20) not null, debug_session_id varchar(160), final_tick integer, identity_json text not null, next_action_sequence integer not null, next_tick_sequence integer not null, operation varchar(30), operation_attempts integer not null, operation_correlation_id varchar(160), operation_request_hash varchar(71), operation_state varchar(20), recording_hash varchar(71), recording_id varchar(160) unique, request_idempotency_json text not null, session_id varchar(160) not null unique, start_idempotency_key varchar(240) not null unique, start_request_hash varchar(71) not null, start_tick bigint not null, started_at timestamp(6) with time zone not null, tick_idempotency_json text not null, tick_sequences_json text not null, ticks_json text not null, version bigint, video_path varchar(500), video_sha256 varchar(71), primary key (id));
create table routine_curations (id bigint generated by default as identity, created_at timestamp(6) with time zone not null, exclude_ranges_json text, include_ranges_json text, recording_id varchar(160) not null unique, primary key (id));
create table routine_models (id bigint generated by default as identity, created_at timestamp(6) with time zone not null, definition_json text not null, graph_id varchar(160) not null, identity_json text not null, model_hash varchar(71) not null, model_id varchar(160) not null unique, primary key (id));
create table routine_replay_traces (id bigint generated by default as identity, content_hash varchar(71) not null, created_at timestamp(6) with time zone not null, identity_json text not null, model_id varchar(160) not null, recording_id varchar(160) not null, result_json text not null, trace_id varchar(180) not null unique, trace_mode varchar(30) not null, primary key (id));
create table routine_shadow_decisions (id bigint generated by default as identity, control_allowed boolean not null, created_at timestamp(6) with time zone not null, decision_content_hash varchar(71) not null, decision_ordinal integer not null, model_identity_hash varchar(71) not null, observed_action_json text, outcome varchar(20) not null, recommendation_json text, session_identity_hash varchar(71) not null, shadow_run_id varchar(180) not null, source_batch_sequence integer not null, source_event_index integer not null, source_event_kind varchar(20) not null, status varchar(20) not null, tick bigint not null, primary key (id), constraint idx_routine_shadow_decision_ordinal unique (shadow_run_id, decision_ordinal), constraint idx_routine_shadow_decision_source unique (shadow_run_id, source_event_kind, source_batch_sequence, source_event_index));
create table routine_shadow_runs (id bigint generated by default as identity, completed_at timestamp(6) with time zone, failure_code varchar(120), failure_message varchar(500), graph_cursor integer not null, last_processed_tick bigint, model_id varchar(160) not null, model_identity_json text not null, next_decision_ordinal integer not null, optimistic_version bigint not null, result_hash varchar(71), result_json text, session_id varchar(160) not null, session_identity_json text not null, shadow_run_id varchar(180) not null unique, started_at timestamp(6) with time zone not null, state varchar(20) not null, primary key (id), constraint idx_routine_shadow_model_session unique (model_id, session_id));
create table scenario_manifests (id uuid not null, action_coverage_pct NUMERIC(5,2) not null, approximation_count integer not null, cache_revision varchar(255) not null, compiler_schema_version integer not null, compiler_version varchar(255) not null, completeness varchar(255) not null, content_revision varchar(255) not null, conversion_quality varchar(32) not null, created_at timestamp(6) not null, curation_id uuid not null, debug_session_checksum varchar(255) not null, debug_session_id varchar(255) not null, end_tick bigint not null, fidelity varchar(32) not null, fixed_clock_origin timestamp(6) not null, gap_count integer not null, manifest_checksum varchar(255) not null unique, manifest_json jsonb not null, manifest_version integer not null, publish_state varchar(255) not null, recording_checksum varchar(255) not null, recording_id varchar(255) not null, rejection_json jsonb, seed varchar(255) not null, start_tick bigint not null, viewer_trace_checksum varchar(71), viewer_trace_frame_count bigint, xrsps_adapter_version varchar(255) not null, xrsps_build_commit varchar(255) not null, primary key (id));
create table script_events (id bigint generated by default as identity, event_type varchar(20) not null check (event_type in ('START','STOP')), script_name varchar(100) not null, timestamp timestamp(6) not null, primary key (id));
create table script_failure_triage (id bigint generated by default as identity, failure_signature varchar(255) not null, first_seen_at timestamp(6) not null, last_error_message TEXT, last_report_id varchar(100), last_seen_at timestamp(6) not null, occurrences integer not null, script_key varchar(200) not null, status varchar(50) not null, triage_key varchar(255) not null unique, primary key (id));
create table script_stabilization_run (id bigint generated by default as identity, artifact_digest varchar(200), attempt_id varchar(100), build_id varchar(200), catalog_hash varchar(200), checksums_reference varchar(255), debug_session_id varchar(200), deterministic_content_hash varchar(200), duration_ms bigint, entry_id varchar(100), environment varchar(50), error_message TEXT, evidence_complete boolean, evidence_json TEXT, evidence_reference varchar(255), executed_at timestamp(6) not null, executor_instance_id varchar(200), failure_signature varchar(255), iteration_id varchar(100), manifest_hash varchar(200), manifest_reference varchar(255), matrix_reference varchar(255), outcome varchar(20) not null, plan_hash varchar(200), primary_failure_code varchar(100), recording_id varchar(200), report_id varchar(100) not null, run_id varchar(100), schema_version varchar(50), script_key varchar(200) not null, settings_hash varchar(200), source_snapshot_id varchar(100), status varchar(50), tier varchar(50), transitions_json TEXT, transitions_reference varchar(255), verified boolean not null, primary key (id));
create table script_stabilization_snapshot (id bigint generated by default as identity, artifact_digest varchar(200), baseline_pass_rate24h float(53) not null, baseline_report_id varchar(100), build_id varchar(200), captured_at timestamp(6) not null, catalog_hash varchar(200), cleanup_success_rate float(53), comparison_status varchar(50), eventual_pass_rate float(53), evidence_complete_rate float(53), executor_instance_id varchar(200), fail_count24h integer not null, failure_cluster_count integer not null, family varchar(50), first_attempt_pass_rate float(53), group_key varchar(1024), incomparable_json TEXT, matrix_hash varchar(200), matrix_json TEXT, pass_count24h integer not null, pass_rate24h float(53) not null, pass_rate7d float(53) not null, plan_hash varchar(200), regression boolean not null, report_id varchar(100) not null, retry_count integer, script_key varchar(200) not null, source_snapshot_id varchar(200), timeout_count24h integer not null, timeout_rate24h float(53) not null, top_failure_signature varchar(255), window24h_count integer not null, window7d_count integer not null, primary key (id));
create table shadow_sessions (id bigint generated by default as identity, account_name varchar(255), action_count bigint, ended_at timestamp(6), notes TEXT, script_name varchar(255), session_id varchar(255) not null unique, started_at timestamp(6), status varchar(255), tick_count bigint, primary key (id));
create table stabilization_artifact (id bigint generated by default as identity, artifact_id varchar(36) not null, attempt_id varchar(100), created_at timestamp(6) not null, evidence_type varchar(50), external_reference varchar(512) not null, media_type varchar(100), run_id varchar(100) not null, sha256 varchar(200), size_bytes bigint, primary key (id));
create table stabilization_run_aggregate (id bigint generated by default as identity, artifact_digest varchar(100), attempt_count integer not null, build_id varchar(100), catalog_hash varchar(200), deterministic_content_hash varchar(200), environment varchar(50), evidence_complete boolean not null, executed_at timestamp(6) not null, manifest_hash varchar(200), micro6instance_id varchar(100), outcome varchar(20) not null, plan_hash varchar(200), plan_id varchar(100), run_id varchar(100) not null unique, settings_hash varchar(200), source_snapshot_id varchar(100), task_key varchar(100), terminal_state varchar(50), tier varchar(50), primary key (id));
create table state_hash_samples (run_id uuid not null, sequence integer not null, tick bigint not null, chain_hash varchar(255) not null, projection_version integer not null, state_hash varchar(255) not null, primary key (run_id, sequence, tick));
create table task_evidence_citation (id bigint generated by default as identity, created_at timestamp(6) not null, evidence_id bigint not null, evidence_timestamp timestamp(6), evidence_type smallint not null check (evidence_type between 0 and 4), item_id varchar(100) not null, plan_id varchar(100) not null, relevance_score float(53), snippet varchar(500), primary key (id));
create table task_plan (id bigint generated by default as identity, account_name varchar(200), approved_at timestamp(6), completed_at timestamp(6), created_at timestamp(6) not null, intent TEXT not null, plan_id varchar(100) not null unique, source_recording_id varchar(100), source_session_id varchar(100), status varchar(50) not null check (status in ('GENERATED','UNDER_REVIEW','APPROVED','PARTIALLY_APPROVED','REJECTED','EXECUTING','COMPLETED','FAILED')), synthesis_metadata jsonb, updated_at timestamp(6), primary key (id));
create table task_plan_item (id bigint generated by default as identity, approved_at timestamp(6), completed_at timestamp(6), created_at timestamp(6) not null, description TEXT, difficulty varchar(20), estimated_minutes integer, executed_at timestamp(6), execution_result TEXT, item_id varchar(100) not null unique, patch_loop_iteration_id varchar(100), plan_id varchar(100) not null, requirements jsonb, script_name varchar(200), sort_order integer not null, status varchar(50) not null check (status in ('PENDING','APPROVED','REJECTED','EXECUTING','COMPLETED','FAILED')), title varchar(500) not null, primary key (id));
create table task_recording_preferences (id bigint generated by default as identity, created_at timestamp(6) not null, enabled boolean not null, task_type varchar(50) not null unique, updated_at timestamp(6) not null, primary key (id));
create table tick_logs (id bigint generated by default as identity, correlation_id uuid, details jsonb, event_type varchar(100), message TEXT not null, session_id varchar(100) not null, severity varchar(20) not null, subsystem varchar(50) not null, tick bigint not null, timestamp timestamp(6) not null, primary key (id));
create table tick_overlays (id bigint generated by default as identity, created_at timestamp(6) not null, data jsonb not null, overlay_type varchar(50) not null, session_id varchar(100) not null, tick bigint not null, primary key (id));
create table tick_snapshots (id bigint generated by default as identity, cooldowns jsonb, equipment jsonb, game_objects jsonb, ground_items jsonb, ground_objects jsonb, inventory jsonb, npcs jsonb, player_state jsonb not null, players jsonb, projectiles jsonb, quest jsonb, quest_helper jsonb, schema_version integer, session_id varchar(100) not null, threats jsonb, tick bigint not null, timestamp timestamp(6) not null, widgets jsonb, primary key (id));
create table timeline_markers (id bigint generated by default as identity, created_at timestamp(6) not null, description TEXT, is_auto_generated boolean not null, label varchar(255), log_id bigint, marker_type varchar(50) not null, session_id varchar(100) not null, severity varchar(20), tick bigint not null, primary key (id));
create index idx_debug_sessions_recording_id on debug_sessions (recording_id);
create index idx_debug_sessions_start_time on debug_sessions (start_time);
create index idx_logs_debug_session_id_timestamp on logs (debug_session_id, timestamp);
create index idx_logs_recording_id_timestamp on logs (recording_id, timestamp);
create index idx_event_iteration_id on patch_loop_event (iteration_id);
create index idx_event_timestamp on patch_loop_event (timestamp);
create index idx_recording_sessions_start_time on recording_sessions (start_time);
create index idx_recording_sessions_recording_id on recording_sessions (recording_id);
create index idx_replay_recording_actions_recording_tick on replay_recording_actions (recording_id, tick, sequence);
create index idx_replay_recording_actions_session_tick on replay_recording_actions (session_id, tick, sequence);
create index idx_routine_capture_batch_session on routine_capture_batches (session_id, accepted_at);
create index idx_routine_capture_operations_claim on routine_capture_operations (state, next_attempt_at, lease_expires_at, created_at);
create index idx_routine_capture_operations_session on routine_capture_operations (session_id, operation, state);
create index idx_tick_logs_session_tick on tick_logs (session_id, tick);
create index idx_tick_logs_severity on tick_logs (session_id, severity);
create index idx_tick_overlays_session_tick on tick_overlays (session_id, tick);
create index idx_tick_snapshots_session on tick_snapshots (session_id);
create index idx_tick_snapshots_session_tick on tick_snapshots (session_id, tick);
create index idx_timeline_markers_session_tick on timeline_markers (session_id, tick);
alter table if exists field_provenance add constraint FKob1a29opuebyfpcd6wegx09t8 foreign key (curation_id) references recording_curations;

-- Canonical SC-810 source persistence, constraints and immutability.
-- SC-810: immutable source-manifest references and exact SC-609 run bindings.
CREATE TABLE IF NOT EXISTS source_manifest_references (
    id UUID PRIMARY KEY,
    version BIGINT,
    task_key VARCHAR(160) NOT NULL,
    schema_version VARCHAR(80) NOT NULL,
    manifest_hash VARCHAR(71) NOT NULL,
    snapshot_id VARCHAR(40) NOT NULL,
    repository_id VARCHAR(200),
    commit_id VARCHAR(64),
    tree_id VARCHAR(64),
    build_id VARCHAR(200),
    artifact_digest VARCHAR(71),
    verification_state VARCHAR(40) NOT NULL,
    source_provenance VARCHAR(40) NOT NULL,
    retention_class VARCHAR(40),
    confidentiality VARCHAR(40),
    environment VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    pruned_at TIMESTAMP WITH TIME ZONE,
    redacted_at TIMESTAMP WITH TIME ZONE,
    manifest_json TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_manifest_references_manifest_hash
    ON source_manifest_references (manifest_hash);
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_manifest_references_snapshot_id
    ON source_manifest_references (snapshot_id);

CREATE TABLE IF NOT EXISTS source_run_bindings (
    id UUID PRIMARY KEY,
    run_id VARCHAR(160) NOT NULL,
    recording_id VARCHAR(160),
    task_key VARCHAR(160) NOT NULL,
    manifest_hash VARCHAR(71) NOT NULL,
    snapshot_id VARCHAR(40) NOT NULL,
    artifact_digest VARCHAR(71),
    environment VARCHAR(120),
    verification_state VARCHAR(40) NOT NULL,
    source_provenance VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_run_bindings_run_id ON source_run_bindings (run_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_run_bindings_recording_id
    ON source_run_bindings (recording_id) WHERE recording_id IS NOT NULL;
ALTER TABLE source_run_bindings ADD COLUMN IF NOT EXISTS environment VARCHAR(120);

-- Content-addressed bytes are immutable.  Retention markers are the only mutable fields, and
-- they may move only from NULL to a timestamp.  A content object can be shared by several
-- manifests, so access is also gated by the manifest reference's retention state.
CREATE TABLE IF NOT EXISTS source_content_objects (
    id UUID PRIMARY KEY,
    content_hash VARCHAR(71) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_bytes BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    pruned_at TIMESTAMP WITH TIME ZONE,
    redacted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT source_content_hash_format CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT source_content_size_nonnegative CHECK (size_bytes >= 0),
    CONSTRAINT source_content_size_matches CHECK (size_bytes = octet_length(content_bytes))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_content_objects_content_hash
    ON source_content_objects (content_hash);

-- Bindings refer to an already persisted immutable manifest.  This prevents a run row from
-- pointing at a source identity that was never verified by ChromaScape.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_source_run_bindings_manifest_hash'
          AND conrelid = 'source_run_bindings'::regclass) THEN
        ALTER TABLE source_run_bindings
            ADD CONSTRAINT fk_source_run_bindings_manifest_hash
            FOREIGN KEY (manifest_hash)
            REFERENCES source_manifest_references (manifest_hash);
    END IF;
END $$;

CREATE OR REPLACE FUNCTION sc810_immutable_manifest_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.task_key IS DISTINCT FROM OLD.task_key
       OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
       OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
       OR NEW.snapshot_id IS DISTINCT FROM OLD.snapshot_id
       OR NEW.repository_id IS DISTINCT FROM OLD.repository_id
       OR NEW.commit_id IS DISTINCT FROM OLD.commit_id
       OR NEW.tree_id IS DISTINCT FROM OLD.tree_id
       OR NEW.build_id IS DISTINCT FROM OLD.build_id
       OR NEW.artifact_digest IS DISTINCT FROM OLD.artifact_digest
       OR NEW.verification_state IS DISTINCT FROM OLD.verification_state
       OR NEW.source_provenance IS DISTINCT FROM OLD.source_provenance
       OR NEW.retention_class IS DISTINCT FROM OLD.retention_class
       OR NEW.confidentiality IS DISTINCT FROM OLD.confidentiality
       OR NEW.environment IS DISTINCT FROM OLD.environment
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.manifest_json IS DISTINCT FROM OLD.manifest_json
       OR (OLD.pruned_at IS NOT NULL AND NEW.pruned_at IS DISTINCT FROM OLD.pruned_at)
       OR (OLD.redacted_at IS NOT NULL AND NEW.redacted_at IS DISTINCT FROM OLD.redacted_at)
       OR (NEW.pruned_at IS NOT NULL AND NEW.pruned_at < OLD.pruned_at)
       OR (NEW.redacted_at IS NOT NULL AND NEW.redacted_at < OLD.redacted_at) THEN
        RAISE EXCEPTION 'SC-810 source manifest identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sc810_immutable_manifest_reference
    ON source_manifest_references;
CREATE TRIGGER trg_sc810_immutable_manifest_reference
BEFORE UPDATE ON source_manifest_references
FOR EACH ROW EXECUTE FUNCTION sc810_immutable_manifest_reference();

CREATE OR REPLACE FUNCTION sc810_immutable_content_object()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
       OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
       OR NEW.content_bytes IS DISTINCT FROM OLD.content_bytes
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR (OLD.pruned_at IS NOT NULL AND NEW.pruned_at IS DISTINCT FROM OLD.pruned_at)
       OR (OLD.redacted_at IS NOT NULL AND NEW.redacted_at IS DISTINCT FROM OLD.redacted_at)
       OR (NEW.pruned_at IS NOT NULL AND NEW.pruned_at < OLD.pruned_at)
       OR (NEW.redacted_at IS NOT NULL AND NEW.redacted_at < OLD.redacted_at) THEN
        RAISE EXCEPTION 'SC-810 source content identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sc810_immutable_content_object
    ON source_content_objects;
CREATE TRIGGER trg_sc810_immutable_content_object
BEFORE UPDATE ON source_content_objects
FOR EACH ROW EXECUTE FUNCTION sc810_immutable_content_object();

-- A recording is assigned after a run is created.  That one lifecycle transition is allowed, but
-- neither the run identity nor its source identity may ever be replaced or cleared.
CREATE OR REPLACE FUNCTION sc810_immutable_run_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.task_key IS DISTINCT FROM OLD.task_key
       OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
       OR NEW.snapshot_id IS DISTINCT FROM OLD.snapshot_id
       OR NEW.artifact_digest IS DISTINCT FROM OLD.artifact_digest
       OR NEW.environment IS DISTINCT FROM OLD.environment
       OR NEW.verification_state IS DISTINCT FROM OLD.verification_state
       OR NEW.source_provenance IS DISTINCT FROM OLD.source_provenance
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR (OLD.recording_id IS NOT NULL AND NEW.recording_id IS DISTINCT FROM OLD.recording_id) THEN
        RAISE EXCEPTION 'SC-810 source run identity is immutable';
    END IF;
    IF OLD.recording_id IS NULL AND NEW.recording_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.recording_id IS NULL AND (NEW.recording_id IS NULL OR btrim(NEW.recording_id) = '') THEN
        RAISE EXCEPTION 'SC-810 recording binding must be a nonblank one-way assignment';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sc810_immutable_run_binding
    ON source_run_bindings;
CREATE TRIGGER trg_sc810_immutable_run_binding
BEFORE UPDATE ON source_run_bindings
FOR EACH ROW EXECUTE FUNCTION sc810_immutable_run_binding();

-- Source-read audit records intentionally contain identities and outcomes only; source bytes,
-- authorization credentials, host paths, and upstream URLs never enter this table.
CREATE TABLE IF NOT EXISTS source_read_audit_events (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(160) NOT NULL,
    operation VARCHAR(40) NOT NULL,
    run_id VARCHAR(160),
    recording_id VARCHAR(160),
    task_key VARCHAR(160),
    snapshot_id VARCHAR(40),
    file_id VARCHAR(80),
    content_hash VARCHAR(71),
    result VARCHAR(80) NOT NULL,
    environment VARCHAR(120),
    correlation_id VARCHAR(160),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_source_read_audit_run_created
    ON source_read_audit_events (run_id, created_at);
CREATE INDEX IF NOT EXISTS idx_source_read_audit_snapshot_created
    ON source_read_audit_events (snapshot_id, created_at);
ALTER TABLE source_read_audit_events ADD COLUMN IF NOT EXISTS content_hash VARCHAR(71);
