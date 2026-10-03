-- SC-665 reconciliation with current main. Canonical released migrations remain unchanged.
-- V4/V5 are reserved for the stacked SC-641/SC-609 development schema additions.

-- Canonical source: db/migration/V32__sc1431_recovery_correlation.sql
-- SC-1431.1: immutable, non-secret recovery correlation for post-reservation calls.
-- V23 is already installed in persistent environments and V24-V31 are occupied by later
-- migrations, so this is a forward-only additive migration. No raw request or authority secret is
-- copied into this table.

CREATE TABLE ab_gateway_correlation (
    orchestration_id VARCHAR(128) PRIMARY KEY
        REFERENCES ab_orchestration(orchestration_id) ON DELETE RESTRICT,
    contract_version VARCHAR(32) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    envelope_digest VARCHAR(80) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    source_type VARCHAR(128) NOT NULL,
    task_key VARCHAR(128) NOT NULL,
    authority_id VARCHAR(128) NOT NULL,
    identities_digest VARCHAR(80) NOT NULL,
    settings_digest VARCHAR(80) NOT NULL,
    budgets_digest VARCHAR(80) NOT NULL,
    expected_observation_revision BIGINT NOT NULL,
    expected_task_revision BIGINT NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ab_correlation_contract_version_check
        CHECK (contract_version = 'sc-1431/v1'),
    CONSTRAINT ab_correlation_request_id_check
        CHECK (request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ab_correlation_idempotency_key_check
        CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ab_correlation_envelope_digest_check
        CHECK (envelope_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ab_correlation_mode_check
        CHECK (mode IN ('HELD', 'SHADOW', 'BOOTSTRAP_RECORDING', 'LIVE_TASK', 'SIMULATION')),
    CONSTRAINT ab_correlation_source_type_check
        CHECK (source_type <> '' AND length(source_type) <= 128),
    CONSTRAINT ab_correlation_task_key_check
        CHECK (task_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ab_correlation_authority_id_check
        CHECK (authority_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ab_correlation_identities_digest_check
        CHECK (identities_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ab_correlation_settings_digest_check
        CHECK (settings_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ab_correlation_budgets_digest_check
        CHECK (budgets_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ab_correlation_revision_check
        CHECK (expected_observation_revision >= 0 AND expected_task_revision >= 0)
);

CREATE INDEX ab_gateway_correlation_request_idx
    ON ab_gateway_correlation(request_id);

CREATE INDEX ab_gateway_correlation_idempotency_idx
    ON ab_gateway_correlation(idempotency_key);

CREATE TRIGGER ab_gateway_correlation_append_only
    BEFORE UPDATE OR DELETE ON ab_gateway_correlation
    FOR EACH ROW EXECUTE FUNCTION ab_reject_evidence_mutation();

-- V23 shipped an overly broad digest check: verifier_digest is the bounded, non-secret
-- verifier identifier (for example, "verifier-v1"), not a cryptographic digest. Keep V23
-- immutable and correct that contract in the forward migration while retaining digest checks
-- for every actual digest column.
ALTER TABLE ab_snapshot_binding
    DROP CONSTRAINT IF EXISTS ab_binding_digest_check;

ALTER TABLE ab_snapshot_binding
    ADD CONSTRAINT ab_binding_digest_check CHECK (
        decision_digest ~ '^sha256:[0-9a-f]{64}$'
        AND semantic_action_digest ~ '^sha256:[0-9a-f]{64}$'
        AND catalog_digest ~ '^sha256:[0-9a-f]{64}$'
        AND capability_digest ~ '^sha256:[0-9a-f]{64}$'
        AND goal_plan_digest ~ '^sha256:[0-9a-f]{64}$'
        AND tool_schema_digest ~ '^sha256:[0-9a-f]{64}$'
        AND knowledge_digest ~ '^sha256:[0-9a-f]{64}$'
        AND policy_digest ~ '^sha256:[0-9a-f]{64}$'
        AND verifier_digest ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    );


-- Canonical source: db/migration/V35__replay_tick_batch_receipts.sql
-- V35: durable application-level acknowledgment boundary for Micro6 tick batches.
-- debug_sessions is Flyway-owned since V24, so the foreign key applies unconditionally.
CREATE TABLE IF NOT EXISTS replay_tick_batch_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(100) NOT NULL,
    batch_id UUID NOT NULL,
    tick BIGINT NOT NULL CHECK (tick >= 0),
    payload_checksum VARCHAR(71) NOT NULL CHECK (payload_checksum ~ '^sha256:[0-9a-f]{64}$'),
    schema_name VARCHAR(64) NOT NULL CHECK (schema_name = 'tick-batch/v2'),
    persisted_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_replay_tick_batch_receipt_session_batch UNIQUE (session_id, batch_id),
    CONSTRAINT uq_replay_tick_batch_receipt_session_tick UNIQUE (session_id, tick)
);

DO $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'fk_replay_tick_batch_receipt_session'
        ) THEN
        ALTER TABLE replay_tick_batch_receipts
            ADD CONSTRAINT fk_replay_tick_batch_receipt_session
            FOREIGN KEY (session_id) REFERENCES debug_sessions(session_id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_replay_tick_batch_receipt_session_tick
    ON replay_tick_batch_receipts (session_id, tick);


-- Canonical source: db/migration/V39__sc1618_packet_observations.sql
-- V39: durable RSProx packet observations for SC-1618.
-- One row per rsprox-packet/v1 envelope, written in the tick-batch transaction before ACK.
-- Unique (session_id, source_session_id, packet_sequence) gives retry idempotency.
-- debug_sessions is Flyway-owned since V24, so the foreign key applies unconditionally.
-- The unqualified reference resolves through the CURRENT search_path schema, matching V24/V35.
CREATE TABLE IF NOT EXISTS packet_observations (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    tick BIGINT NOT NULL,
    source_session_id VARCHAR(100) NOT NULL,
    packet_sequence BIGINT NOT NULL,
    captured_at TIMESTAMP,
    capture_delta_millis BIGINT,
    direction VARCHAR(20) NOT NULL,
    opcode INTEGER NOT NULL,
    protocol_name VARCHAR(128) NOT NULL,
    encoded_size INTEGER NOT NULL,
    decoded_type VARCHAR(128),
    decoded_payload JSONB,
    sanitized_payload BYTEA,
    decode_status VARCHAR(20) NOT NULL,
    decode_error VARCHAR(512),
    synthetic BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_packet_observations_session_source_sequence
        UNIQUE (session_id, source_session_id, packet_sequence),
    CONSTRAINT chk_packet_observations_direction
        CHECK (direction IN ('CLIENT_TO_SERVER', 'SERVER_TO_CLIENT')),
    CONSTRAINT chk_packet_observations_status
        CHECK (decode_status IN ('DECODED', 'UNKNOWN_PROTOCOL', 'DECODE_ERROR', 'GAP'))
);

CREATE INDEX IF NOT EXISTS idx_packet_observations_session_tick_seq
    ON packet_observations (session_id, tick, packet_sequence);
CREATE INDEX IF NOT EXISTS idx_packet_observations_direction
    ON packet_observations (session_id, direction);
CREATE INDEX IF NOT EXISTS idx_packet_observations_protocol
    ON packet_observations (session_id, protocol_name);
CREATE INDEX IF NOT EXISTS idx_packet_observations_status
    ON packet_observations (session_id, decode_status);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE c.conname = 'fk_packet_observations_session'
          AND t.relname = 'packet_observations'
    ) THEN
        ALTER TABLE packet_observations
            ADD CONSTRAINT fk_packet_observations_session
            FOREIGN KEY (session_id)
            REFERENCES debug_sessions(session_id)
            ON DELETE CASCADE;
    END IF;
END $$;


-- Canonical source: db/migration/V40__sc1449_live_gameplay_gate.sql
-- SC-1449: durable SC-1429/v2 live gameplay gate storage. Replaces the in-memory
-- idempotency, authority, request-row and lease-slot maps so reservation, authority
-- consumption and reconciliation survive a ChromaScape restart.

CREATE TABLE gameplay_v2_gate_request (
    request_id       VARCHAR(128) PRIMARY KEY,
    idempotency_key  VARCHAR(200) NOT NULL,
    fingerprint      VARCHAR(128) NOT NULL,
    envelope_digest  VARCHAR(128) NOT NULL,
    authority_id     VARCHAR(128) NOT NULL,
    account_id       VARCHAR(128) NOT NULL,
    client_id        VARCHAR(128) NOT NULL,
    lease_generation BIGINT       NOT NULL,
    fence            BIGINT       NOT NULL,
    state            VARCHAR(32)  NOT NULL,
    failure_code     VARCHAR(64),
    acknowledged     BOOLEAN      NOT NULL DEFAULT FALSE,
    request_bytes    BYTEA        NOT NULL,
    receipt_json     TEXT,
    evidence_refs    TEXT         NOT NULL DEFAULT '',
    entity_version   BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_gameplay_v2_gate_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_gameplay_v2_gate_authority UNIQUE (authority_id),
    CONSTRAINT uq_gameplay_v2_gate_slot UNIQUE (account_id, client_id, lease_generation, fence)
);

CREATE INDEX idx_gameplay_v2_gate_state ON gameplay_v2_gate_request (state);


-- Canonical source: db/migration/V47__builder_goal_recovery_current_contract.sql
-- SC-1173 compile recovery: current contract persistence.
-- Additive to the partially deployed V9/V16 development schema; clean development databases
-- receive only this contract. Cross-parent composite foreign keys make an artifact, job, or
-- candidate unresolvable through any run other than its own incident.

CREATE TABLE IF NOT EXISTS builder_goal_recovery_incident (
    incident_id VARCHAR(80) PRIMARY KEY,
    iteration_id VARCHAR(100) NOT NULL,
    incident_type VARCHAR(40) NOT NULL
        CONSTRAINT bg_recovery_incident_type CHECK (incident_type IN ('COMPILE_PREFLIGHT', 'RUN_EVIDENCE')),
    failure_code VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(160) NOT NULL,
    run_identity_json TEXT NOT NULL,
    evidence_manifest_json TEXT,
    evidence_manifest_hash VARCHAR(71),
    state VARCHAR(40) NOT NULL DEFAULT 'INCIDENT_CAPTURED',
    created_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_bg_recovery_incident_identity
    ON builder_goal_recovery_incident(iteration_id, evidence_manifest_hash);

CREATE INDEX IF NOT EXISTS idx_bg_recovery_incident_iteration
    ON builder_goal_recovery_incident(iteration_id, created_at);

CREATE TABLE IF NOT EXISTS builder_goal_recovery_artifact (
    artifact_id VARCHAR(80) PRIMARY KEY,
    incident_id VARCHAR(80) NOT NULL REFERENCES builder_goal_recovery_incident(incident_id),
    attempt_id VARCHAR(80),
    kind VARCHAR(50) NOT NULL
        CONSTRAINT bg_recovery_artifact_kind CHECK (kind IN
            ('ORIGINAL_COMPILE_LOG', 'COMPILER_DIAGNOSTICS', 'AI_PROMPT', 'PROPOSAL', 'POLICY_REPORT',
             'PATCH_APPLY_LOG', 'COMPILE_LOG', 'STATIC_LOG', 'TEST_LOG', 'PACKAGE_LOG', 'SBOM',
             'CANDIDATE_MANIFEST', 'CLEANUP_REPORT')),
    storage_key VARCHAR(500) NOT NULL UNIQUE,
    sha256 VARCHAR(71) NOT NULL,
    byte_count BIGINT NOT NULL CHECK (byte_count >= 0),
    line_count BIGINT NOT NULL CHECK (line_count >= 0),
    sanitization_version VARCHAR(30) NOT NULL,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_bg_recovery_artifact_incident UNIQUE (artifact_id, incident_id)
);

CREATE INDEX IF NOT EXISTS idx_bg_recovery_artifact_incident
    ON builder_goal_recovery_artifact(incident_id, kind, created_at);

CREATE TABLE IF NOT EXISTS micro6_patch_proposal (
    proposal_id VARCHAR(80) PRIMARY KEY,
    incident_id VARCHAR(80) NOT NULL REFERENCES builder_goal_recovery_incident(incident_id),
    version INTEGER NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'AWAITING_REVIEW',
    source_snapshot_id VARCHAR(80) NOT NULL,
    source_manifest_hash VARCHAR(71) NOT NULL,
    repository_id VARCHAR(160) NOT NULL,
    base_commit VARCHAR(64) NOT NULL,
    base_tree VARCHAR(64) NOT NULL,
    base_build_id VARCHAR(160) NOT NULL,
    base_artifact_digest VARCHAR(71) NOT NULL,
    canonical_diff TEXT NOT NULL,
    patch_hash VARCHAR(71) NOT NULL,
    proposal_hash VARCHAR(71) NOT NULL,
    affected_files_json TEXT NOT NULL,
    preimages_json TEXT NOT NULL,
    citations_json TEXT NOT NULL,
    rationale TEXT,
    expected_change TEXT,
    invariants_json TEXT,
    non_goals_json TEXT,
    validation_plan_json TEXT NOT NULL,
    validation_plan_hash VARCHAR(71) NOT NULL,
    policy_report_json TEXT NOT NULL,
    generator_json TEXT NOT NULL,
    prompt_hash VARCHAR(71),
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_micro6_patch_proposal_incident_version UNIQUE (incident_id, version),
    CONSTRAINT uq_micro6_patch_proposal_hash UNIQUE (proposal_hash),
    CONSTRAINT uq_micro6_patch_proposal_incident_id UNIQUE (proposal_id, incident_id)
);

CREATE INDEX IF NOT EXISTS idx_micro6_patch_proposal_incident
    ON micro6_patch_proposal(incident_id, version);

CREATE TABLE IF NOT EXISTS micro6_patch_approval (
    approval_id VARCHAR(80) PRIMARY KEY,
    proposal_id VARCHAR(80) NOT NULL REFERENCES micro6_patch_proposal(proposal_id),
    proposal_hash VARCHAR(71) NOT NULL,
    source_binding_hash VARCHAR(71) NOT NULL,
    preimage_set_hash VARCHAR(71) NOT NULL,
    validation_plan_hash VARCHAR(71) NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    actor_role VARCHAR(60) NOT NULL,
    reason TEXT NOT NULL,
    environment VARCHAR(60) NOT NULL,
    policy_version VARCHAR(30) NOT NULL,
    approved_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    approval_hash VARCHAR(71) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT micro6_patch_approval_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED', 'INVALIDATED')),
    CONSTRAINT uq_micro6_patch_approval_proposal UNIQUE (approval_id, proposal_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_micro6_patch_approval_active
    ON micro6_patch_approval(proposal_hash)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS micro6_patch_validation_job (
    job_id VARCHAR(80) PRIMARY KEY,
    proposal_id VARCHAR(80) NOT NULL REFERENCES micro6_patch_proposal(proposal_id),
    approval_id VARCHAR(80) NOT NULL REFERENCES micro6_patch_approval(approval_id),
    incident_id VARCHAR(80) NOT NULL REFERENCES builder_goal_recovery_incident(incident_id),
    state VARCHAR(40) NOT NULL DEFAULT 'QUEUED',
    idempotency_key VARCHAR(160) NOT NULL,
    input_hash VARCHAR(71) NOT NULL,
    toolchain_hash VARCHAR(71) NOT NULL,
    attempt_number INTEGER NOT NULL DEFAULT 0
        CONSTRAINT micro6_patch_validation_attempt CHECK (attempt_number >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 2,
    worker_id VARCHAR(160),
    lease_token VARCHAR(80),
    lease_expires_at TIMESTAMP,
    current_stage VARCHAR(60),
    stage_started_at TIMESTAMP,
    cancel_requested_at TIMESTAMP,
    heartbeat_at TIMESTAMP,
    resulting_tree_hash VARCHAR(71),
    candidate_id VARCHAR(80),
    failure_code VARCHAR(100),
    failure_detail VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT uq_micro6_patch_validation_job_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_micro6_patch_validation_job_proposal UNIQUE (job_id, proposal_id),
    CONSTRAINT uq_micro6_patch_validation_job_incident UNIQUE (job_id, incident_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_micro6_patch_validation_active_input
    ON micro6_patch_validation_job(input_hash)
    WHERE state IN ('QUEUED', 'CLAIMED', 'RUNNING', 'CANCELLING');

CREATE TABLE IF NOT EXISTS micro6_candidate_artifact (
    candidate_id VARCHAR(80) PRIMARY KEY,
    job_id VARCHAR(80) NOT NULL REFERENCES micro6_patch_validation_job(job_id),
    incident_id VARCHAR(80) NOT NULL REFERENCES builder_goal_recovery_incident(incident_id),
    proposal_id VARCHAR(80) NOT NULL REFERENCES micro6_patch_proposal(proposal_id),
    candidate_digest VARCHAR(71) NOT NULL,
    resulting_tree_hash VARCHAR(71) NOT NULL,
    package_storage_key VARCHAR(500) NOT NULL UNIQUE,
    package_sha256 VARCHAR(71) NOT NULL,
    package_size BIGINT NOT NULL CHECK (package_size >= 0),
    sbom_storage_key VARCHAR(500) NOT NULL,
    sbom_sha256 VARCHAR(71) NOT NULL,
    manifest_json TEXT NOT NULL,
    manifest_hash VARCHAR(71) NOT NULL,
    signature TEXT NOT NULL,
    signing_key_id VARCHAR(120) NOT NULL,
    validation_report_json TEXT NOT NULL,
    eligibility_state VARCHAR(40) NOT NULL DEFAULT 'BUILD_VALIDATED',
    created_at TIMESTAMP NOT NULL,
    quarantined_at TIMESTAMP,
    CONSTRAINT uq_micro6_candidate_job UNIQUE (candidate_id, job_id),
    CONSTRAINT uq_micro6_candidate_digest UNIQUE (candidate_digest)
);

CREATE TABLE IF NOT EXISTS builder_goal_candidate_rerun (
    rerun_request_id VARCHAR(80) PRIMARY KEY,
    incident_id VARCHAR(80) NOT NULL REFERENCES builder_goal_recovery_incident(incident_id),
    candidate_id VARCHAR(80) NOT NULL REFERENCES micro6_candidate_artifact(candidate_id),
    parent_iteration_id VARCHAR(100) NOT NULL,
    child_iteration_id VARCHAR(100),
    candidate_digest VARCHAR(71) NOT NULL,
    scenario_hash VARCHAR(71) NOT NULL,
    account_hash VARCHAR(160) NOT NULL,
    machine_uuid VARCHAR(160) NOT NULL,
    authority_id VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    state VARCHAR(40) NOT NULL DEFAULT 'RERUN_REQUESTED',
    executor_request_id VARCHAR(160),
    reconciliation_json TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT uq_bg_candidate_rerun_idempotency UNIQUE (parent_iteration_id, candidate_digest, idempotency_key),
    CONSTRAINT uq_bg_candidate_rerun_incident UNIQUE (rerun_request_id, incident_id)
);

CREATE TABLE IF NOT EXISTS builder_goal_recovery_audit_event (
    audit_event_id VARCHAR(80) PRIMARY KEY,
    occurred_at TIMESTAMP NOT NULL,
    actor_id VARCHAR(160),
    actor_role VARCHAR(60),
    action VARCHAR(80) NOT NULL,
    iteration_id VARCHAR(100),
    incident_id VARCHAR(80),
    subject_type VARCHAR(60),
    subject_id VARCHAR(160),
    outcome VARCHAR(20) NOT NULL,
    error_code VARCHAR(100),
    detail_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_bg_recovery_audit_incident
    ON builder_goal_recovery_audit_event(incident_id, occurred_at);


-- Canonical source: db/migration/V49__add_behavior_graph_editor_drafts.sql
-- SC-728 editor drafts are deliberately separate from SC-967's immutable v1 graph rows.
CREATE TABLE behavior_graph_drafts (
  draft_id VARCHAR(64) PRIMARY KEY,
  graph_id VARCHAR(64) NOT NULL UNIQUE,
  base_graph_id VARCHAR(64),
  task_key VARCHAR(64) NOT NULL,
  profile_id VARCHAR(128) NOT NULL,
  profile_version VARCHAR(64) NOT NULL,
  revision BIGINT NOT NULL CHECK (revision > 0),
  definition_schema VARCHAR(64) NOT NULL,
  definition_json TEXT NOT NULL,
  graph_hash VARCHAR(71) NOT NULL,
  layout_schema VARCHAR(64) NOT NULL,
  layout_json TEXT NOT NULL,
  layout_hash VARCHAR(71) NOT NULL,
  state VARCHAR(16) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(128),
  updated_by VARCHAR(128)
);

CREATE TABLE behavior_graph_publications (
  publication_id BIGSERIAL PRIMARY KEY,
  graph_id VARCHAR(64) NOT NULL,
  graph_version BIGINT NOT NULL CHECK (graph_version > 0),
  source_draft_id VARCHAR(64) NOT NULL,
  source_draft_revision BIGINT NOT NULL CHECK (source_draft_revision > 0),
  definition_schema VARCHAR(64) NOT NULL,
  definition_json TEXT NOT NULL,
  graph_hash VARCHAR(71) NOT NULL,
  layout_schema VARCHAR(64) NOT NULL,
  layout_json TEXT NOT NULL,
  layout_hash VARCHAR(71) NOT NULL,
  published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_by VARCHAR(128),
  CONSTRAINT uq_behavior_graph_publication_version UNIQUE (graph_id, graph_version),
  CONSTRAINT uq_behavior_graph_publication_source UNIQUE (source_draft_id, source_draft_revision)
);


-- Canonical source: db/migration/V58__sc1431_lifecycle_budget_counters.sql
-- SC-1431.2: durable lifecycle budget counters for bounded AccountBuilder orchestration.
-- Limits are immutable once claimed; only the used counters advance through atomic conditional
-- updates. No authority signature, key, token secret or raw fence token is stored here.

CREATE TABLE ab_orchestration_budget (
    orchestration_id VARCHAR(128) PRIMARY KEY
        REFERENCES ab_orchestration(orchestration_id) ON DELETE RESTRICT,
    max_actions INTEGER NOT NULL,
    max_attempts INTEGER NOT NULL,
    max_elapsed_ms BIGINT NOT NULL,
    actions_used INTEGER NOT NULL DEFAULT 0,
    attempts_used INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ab_budget_actions_limit_check CHECK (max_actions BETWEEN 1 AND 20),
    CONSTRAINT ab_budget_attempts_limit_check CHECK (max_attempts BETWEEN 1 AND 3),
    CONSTRAINT ab_budget_elapsed_limit_check CHECK (max_elapsed_ms BETWEEN 1000 AND 1800000),
    CONSTRAINT ab_budget_actions_used_check CHECK (actions_used >= 0 AND actions_used <= max_actions),
    CONSTRAINT ab_budget_attempts_used_check
        CHECK (attempts_used >= 0 AND attempts_used <= max_attempts),
    CONSTRAINT ab_budget_version_check CHECK (version >= 0)
);


-- Canonical source: db/migration/V33__replay_snapshot_state_sections.sql
-- V33: additive replay snapshot state sections.
-- Existing observed playback remains readable; old rows keep NULL for sections that were not
-- captured by the producer. New typed recordings may persist the complete dialogue/spell/var
-- evidence needed by manifest v2 and the authoritative action validators.

ALTER TABLE IF EXISTS tick_snapshots
    ADD COLUMN IF NOT EXISTS dialogue JSONB,
    ADD COLUMN IF NOT EXISTS spells JSONB,
    ADD COLUMN IF NOT EXISTS vars JSONB;

DO $$
BEGIN
    IF to_regclass(current_schema() || '.tick_snapshots') IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'ck_tick_snapshots_replay_state_json_sizes'
              AND conrelid = 'tick_snapshots'::regclass
        ) THEN
            ALTER TABLE tick_snapshots ADD CONSTRAINT ck_tick_snapshots_replay_state_json_sizes
                CHECK (
                    dialogue IS NULL OR octet_length(dialogue::text) <= 262144
                    AND (spells IS NULL OR octet_length(spells::text) <= 262144)
                    AND (
                    vars IS NULL OR octet_length(vars::text) <= 262144
                ));
        END IF;
    END IF;
END $$;


-- Canonical source: db/migration/V34__replay_recording_action_provenance.sql
-- V34: retain the producer provenance asserted by recording-action/v1.
-- V24 rows predate this field; derive their value from the already closed source enum so the
-- migration does not silently label operator/defaulted evidence as captured.

ALTER TABLE replay_recording_actions
    ADD COLUMN IF NOT EXISTS provenance VARCHAR(32);

UPDATE replay_recording_actions
SET provenance = CASE source
    WHEN 'MICRO6_SEMANTIC_DISPATCH' THEN 'CAPTURED'
    WHEN 'MICRO6_LEGACY_CONVERSION' THEN 'INFERRED'
    WHEN 'OPERATOR_SUPPLIED' THEN 'OPERATOR_SUPPLIED'
    WHEN 'DEFAULTED' THEN 'DEFAULTED'
    ELSE NULL
END
WHERE provenance IS NULL;

ALTER TABLE replay_recording_actions
    ALTER COLUMN provenance SET DEFAULT 'CAPTURED',
    ALTER COLUMN provenance SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'replay_recording_action_provenance_check'
          AND conrelid = 'replay_recording_actions'::regclass
    ) THEN
        ALTER TABLE replay_recording_actions
            ADD CONSTRAINT replay_recording_action_provenance_check
            CHECK (provenance IN ('CAPTURED', 'INFERRED', 'OPERATOR_SUPPLIED', 'DEFAULTED'));
    END IF;
END $$;


-- Canonical source: db/migration/V34_1__durable_debug_table_ownership.sql
-- Additive ownership prerequisite for durable receipt migrations on fresh databases.
-- Preserve the checked-in V10/V24 checksums; old installations may already have applied them.
-- CREATE IF NOT EXISTS retains all pre-existing Hibernate-owned data.
CREATE TABLE IF NOT EXISTS recording_sessions (
    id BIGSERIAL PRIMARY KEY,
    recording_id VARCHAR(100) NOT NULL,
    account_name VARCHAR(100),
    script_name VARCHAR(100),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    trigger_type VARCHAR(20) NOT NULL,
    video_path VARCHAR(500),
    duration_ms BIGINT,
    frame_count BIGINT,
    fps INTEGER,
    video_codec VARCHAR(50),
    replay_name VARCHAR(160),
    archived_at TIMESTAMP NULL,
    replay_tags_json TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_recording_sessions_recording_id
    ON recording_sessions (recording_id);
CREATE INDEX IF NOT EXISTS idx_recording_sessions_start_time
    ON recording_sessions (start_time);
CREATE INDEX IF NOT EXISTS idx_recording_sessions_recording_id
    ON recording_sessions (recording_id);

ALTER TABLE recording_sessions
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_recording_sessions_archived_at
    ON recording_sessions (archived_at);
CREATE TABLE IF NOT EXISTS debug_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    account_name VARCHAR(100),
    script_name VARCHAR(100),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    start_tick BIGINT NOT NULL,
    end_tick BIGINT,
    recording_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    lifecycle_state VARCHAR(20),
    failure_code VARCHAR(50),
    failure_reason VARCHAR(500),
    failure_context VARCHAR(2000),
    last_state_at TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_debug_sessions_session_id
    ON debug_sessions (session_id);
CREATE INDEX IF NOT EXISTS idx_debug_sessions_recording_id
    ON debug_sessions (recording_id);
CREATE INDEX IF NOT EXISTS idx_debug_sessions_start_time
    ON debug_sessions (start_time);


DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_replay_recording_actions_session'
          AND conrelid = 'replay_recording_actions'::regclass
    ) THEN
        ALTER TABLE replay_recording_actions
            ADD CONSTRAINT fk_replay_recording_actions_session
            FOREIGN KEY (session_id) REFERENCES debug_sessions(session_id) ON DELETE RESTRICT;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_replay_recording_actions_recording'
          AND conrelid = 'replay_recording_actions'::regclass
    ) THEN
        ALTER TABLE replay_recording_actions
            ADD CONSTRAINT fk_replay_recording_actions_recording
            FOREIGN KEY (recording_id) REFERENCES recording_sessions(recording_id) ON DELETE RESTRICT;
    END IF;
END $$;


-- Canonical source: db/migration/V42__sc1449_gate_expiry.sql
-- SC-1449: add the envelope deadline and human-authority expiry to the v2 gate table.
--
-- V40 created the table without these columns and must never be edited again: a database that
-- already applied V40 would fail Flyway validation on a changed historical checksum, and
-- ADD COLUMN IF NOT EXISTS cannot repair a checksum. The columns are therefore introduced here.
--
-- The columns are added nullable so existing rows survive the ALTER, then every null is resolved
-- before NOT NULL is applied:
--   * reconstructable rows take their exact values from the persisted canonical request bytes,
--     which are the same bytes that passed validation and bound the authority;
--   * unreconstructable rows get the epoch, which is unconditionally in the past, so the
--     start-time deadline and expiry checks fail closed instead of skipping a null and failing
--     open, and any non-terminal row is moved to NEEDS_RECONCILIATION so an operator sees it.
-- Terminal rows keep their proven outcome and are never walked backward.

ALTER TABLE gameplay_v2_gate_request
    ADD COLUMN IF NOT EXISTS envelope_deadline TIMESTAMPTZ;

ALTER TABLE gameplay_v2_gate_request
    ADD COLUMN IF NOT EXISTS authority_expires_at TIMESTAMPTZ;

DO $$
DECLARE
    gate_row          RECORD;
    parsed            jsonb;
    parsed_deadline   timestamptz;
    parsed_expiry     timestamptz;
BEGIN
    FOR gate_row IN
        SELECT request_id, request_bytes
        FROM gameplay_v2_gate_request
        WHERE envelope_deadline IS NULL OR authority_expires_at IS NULL
    LOOP
        parsed_deadline := NULL;
        parsed_expiry := NULL;
        BEGIN
            parsed := convert_from(gate_row.request_bytes, 'UTF8')::jsonb;
            parsed_deadline := (parsed -> 'executionEnvelope' ->> 'deadline')::timestamptz;
            parsed_expiry := (parsed -> 'authority' ->> 'expiresAt')::timestamptz;
        EXCEPTION
            WHEN OTHERS THEN
                -- Any unreadable, non-JSON or structurally different payload is treated as
                -- unreconstructable. Guessing a validity window is never safe.
                parsed_deadline := NULL;
                parsed_expiry := NULL;
        END;

        IF parsed_deadline IS NOT NULL AND parsed_expiry IS NOT NULL THEN
            UPDATE gameplay_v2_gate_request
            SET envelope_deadline = COALESCE(envelope_deadline, parsed_deadline),
                authority_expires_at = COALESCE(authority_expires_at, parsed_expiry)
            WHERE request_id = gate_row.request_id;
        ELSE
            UPDATE gameplay_v2_gate_request
            SET envelope_deadline =
                    COALESCE(envelope_deadline, TIMESTAMPTZ '1970-01-01 00:00:00+00'),
                authority_expires_at =
                    COALESCE(authority_expires_at, TIMESTAMPTZ '1970-01-01 00:00:00+00'),
                state = CASE
                    WHEN state IN ('CONFIRMED', 'REFUTED', 'UNVERIFIABLE', 'FAILED', 'STOPPED')
                        THEN state
                    ELSE 'NEEDS_RECONCILIATION'
                END,
                failure_code = CASE
                    WHEN state IN ('CONFIRMED', 'REFUTED', 'UNVERIFIABLE', 'FAILED', 'STOPPED')
                        THEN failure_code
                    ELSE 'NEEDS_RECONCILIATION'
                END,
                updated_at = now()
            WHERE request_id = gate_row.request_id;
        END IF;
    END LOOP;
END
$$;

ALTER TABLE gameplay_v2_gate_request
    ALTER COLUMN envelope_deadline SET NOT NULL;

ALTER TABLE gameplay_v2_gate_request
    ALTER COLUMN authority_expires_at SET NOT NULL;


-- Canonical source: db/migration/V43__sc1618_packet_observation_identity_v2.sql
-- SC-1618: separate v2 observation identity from original capture identity.
-- V39 remains the historical v1 migration. Existing rows are explicitly backfilled as v1 so old
-- recordings retain their original meaning and remain readable after this migration.

ALTER TABLE packet_observations
    ADD COLUMN IF NOT EXISTS packet_schema VARCHAR(32),
    ADD COLUMN IF NOT EXISTS observation_sequence BIGINT;

UPDATE packet_observations
SET packet_schema = 'rsprox-packet/v1'
WHERE packet_schema IS NULL;

UPDATE packet_observations
SET observation_sequence = packet_sequence
WHERE observation_sequence IS NULL;

ALTER TABLE packet_observations
    ALTER COLUMN packet_schema SET NOT NULL,
    ALTER COLUMN observation_sequence SET NOT NULL,
    ALTER COLUMN packet_sequence DROP NOT NULL;

ALTER TABLE packet_observations
    DROP CONSTRAINT IF EXISTS uq_packet_observations_session_source_sequence;

ALTER TABLE packet_observations
    DROP CONSTRAINT IF EXISTS chk_packet_observations_schema,
    ADD CONSTRAINT chk_packet_observations_schema
        CHECK (packet_schema IN ('rsprox-packet/v1', 'rsprox-packet/v2')),
    DROP CONSTRAINT IF EXISTS chk_packet_observations_identity,
    ADD CONSTRAINT chk_packet_observations_identity
        CHECK (packet_schema = 'rsprox-packet/v2' OR packet_sequence IS NOT NULL),
    ADD CONSTRAINT uq_packet_observations_session_source_sequence
        UNIQUE (session_id, source_session_id, packet_schema, observation_sequence);

DROP INDEX IF EXISTS idx_packet_observations_session_tick_seq;
CREATE INDEX IF NOT EXISTS idx_packet_observations_session_tick_seq
    ON packet_observations (
        session_id, tick, source_session_id, packet_schema, observation_sequence, id);


-- Canonical source: db/migration/V48__builder_goal_recovery_contract_corrections.sql
-- SC-1173: do not amend V47. These additive corrections make public IDs the durable
-- JPA identity and prevent cross-incident lineage from being represented in PostgreSQL.

ALTER TABLE builder_goal_recovery_incident
    ADD COLUMN IF NOT EXISTS entity_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE micro6_patch_proposal
    ADD COLUMN IF NOT EXISTS entity_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE micro6_patch_approval
    ADD COLUMN IF NOT EXISTS entity_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE builder_goal_candidate_rerun
    ADD COLUMN IF NOT EXISTS entity_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE builder_goal_recovery_artifact
    ADD CONSTRAINT uq_bg_recovery_artifact_identity
    UNIQUE (incident_id, kind, sha256, sanitization_version);

-- Parent composite keys are deliberately explicit: a child must agree on every parent
-- identity it carries, not merely reference independently valid rows.
ALTER TABLE micro6_patch_approval
    ADD CONSTRAINT uq_micro6_patch_approval_id_proposal UNIQUE (approval_id, proposal_id);
ALTER TABLE micro6_patch_validation_job
    ADD CONSTRAINT uq_micro6_patch_validation_job_id_proposal_incident
    UNIQUE (job_id, proposal_id, incident_id);
ALTER TABLE micro6_candidate_artifact
    ADD CONSTRAINT uq_micro6_candidate_id_incident UNIQUE (candidate_id, incident_id);

ALTER TABLE micro6_patch_validation_job
    ADD CONSTRAINT fk_bg_validation_proposal_incident
    FOREIGN KEY (proposal_id, incident_id)
    REFERENCES micro6_patch_proposal (proposal_id, incident_id),
    ADD CONSTRAINT fk_bg_validation_approval_proposal
    FOREIGN KEY (approval_id, proposal_id)
    REFERENCES micro6_patch_approval (approval_id, proposal_id);

ALTER TABLE micro6_candidate_artifact
    ADD CONSTRAINT fk_bg_candidate_job_incident
    FOREIGN KEY (job_id, incident_id)
    REFERENCES micro6_patch_validation_job (job_id, incident_id),
    ADD CONSTRAINT fk_bg_candidate_job_proposal
    FOREIGN KEY (job_id, proposal_id)
    REFERENCES micro6_patch_validation_job (job_id, proposal_id),
    ADD CONSTRAINT fk_bg_candidate_proposal_incident
    FOREIGN KEY (proposal_id, incident_id)
    REFERENCES micro6_patch_proposal (proposal_id, incident_id);

ALTER TABLE builder_goal_candidate_rerun
    ADD CONSTRAINT fk_bg_rerun_candidate_incident
    FOREIGN KEY (candidate_id, incident_id)
    REFERENCES micro6_candidate_artifact (candidate_id, incident_id);

ALTER TABLE builder_goal_recovery_audit_event
    ADD CONSTRAINT fk_bg_recovery_audit_incident
    FOREIGN KEY (incident_id)
    REFERENCES builder_goal_recovery_incident (incident_id);

-- Validation idempotency is scoped to its parent incident. A global key made unrelated
-- recovery cycles conflict and permitted a replay to resolve the wrong lineage.
ALTER TABLE micro6_patch_validation_job
    DROP CONSTRAINT uq_micro6_patch_validation_job_idempotency,
    ADD CONSTRAINT uq_micro6_patch_validation_job_incident_idempotency
    UNIQUE (incident_id, idempotency_key);


-- Canonical source: db/migration/V50__builder_goal_recovery_validation_fencing.sql
-- SC-1173: package content is reusable across independent validation lineages. The candidate
-- record itself remains unique per validation job, which prevents double publication.
ALTER TABLE micro6_candidate_artifact
    DROP CONSTRAINT IF EXISTS uq_micro6_candidate_digest;

CREATE INDEX IF NOT EXISTS idx_micro6_candidate_digest
    ON micro6_candidate_artifact(candidate_digest);

ALTER TABLE micro6_candidate_artifact
    ADD CONSTRAINT uq_micro6_candidate_job_id UNIQUE (job_id);

CREATE INDEX IF NOT EXISTS idx_bg_validation_lease
    ON micro6_patch_validation_job(state, lease_expires_at);


-- Canonical source: db/migration/V51__repair_replay_tick_batch_receipt_id_default.sql
-- Repair legacy replay_tick_batch_receipts tables that predated Flyway ownership.
-- V35 used CREATE TABLE IF NOT EXISTS, so an existing Hibernate-created UUID id column could
-- survive without the gen_random_uuid() default required by the native insertIfAbsent query.
DO $$
BEGIN
    IF to_regclass('replay_tick_batch_receipts') IS NOT NULL THEN
        ALTER TABLE replay_tick_batch_receipts
            ALTER COLUMN id SET DEFAULT gen_random_uuid();
    END IF;
END $$;


-- Canonical source: db/migration/V52__sc810_builder_goal_source_binding_state.sql
-- SC-810: make a builder-goal run's source-binding outcome explicit and queryable.
--
-- source_run_bindings (V21) already models the run -> signed-snapshot binding exactly, but the
-- builder-goal dispatch path never wrote one, so a dispatched run carried no durable record of
-- which source snapshot or artifact it executed against. The binding row is the authoritative
-- record; this column exists so the *absence* of a binding is never silently indistinguishable
-- from a run that was simply never checked.
--
-- Values:
--   BOUND          - a verified source_run_bindings row exists for this run.
--   NOT_CONFIGURED - source trust is not configured on this instance, so no binding was attempted.
--   FAILED         - binding was required and attempted, but did not complete.
-- NULL means the run predates this column (historical rows are deliberately not backfilled: no
-- binding data exists for them, and inventing one would be fabricated provenance).

ALTER TABLE patch_loop_iteration
    ADD COLUMN IF NOT EXISTS source_binding_state VARCHAR(40);

ALTER TABLE patch_loop_iteration
    DROP CONSTRAINT IF EXISTS ck_patch_loop_iteration_source_binding_state;

ALTER TABLE patch_loop_iteration
    ADD CONSTRAINT ck_patch_loop_iteration_source_binding_state
        CHECK (source_binding_state IS NULL
               OR source_binding_state IN ('BOUND', 'NOT_CONFIGURED', 'FAILED'));

CREATE INDEX IF NOT EXISTS ix_patch_loop_iteration_source_binding_state
    ON patch_loop_iteration (source_binding_state);


-- Canonical source: db/migration/V59__sc1431_budget_integrity.sql
-- SC-1431.3: enforce the budget contract in PostgreSQL and persist full provenance bindings.
--
-- V47 introduced the budget table; this migration hardens it. Claimed limits and timestamps are
-- immutable, counters may only advance, and the optimistic-lock version must advance with every
-- update. The snapshot binding table gains the observation and prompt provenance digests that a
-- bindable provider decision must carry.

ALTER TABLE ab_snapshot_binding
    ADD COLUMN observation_digest VARCHAR(80) NOT NULL DEFAULT '',
    ADD COLUMN prompt_digest VARCHAR(80) NOT NULL DEFAULT '';

-- started_at is required by the Java model; backfill any candidate rows from the orchestration
-- root before making the column non-null.
UPDATE ab_orchestration_budget b
SET started_at = o.created_at
FROM ab_orchestration o
WHERE o.orchestration_id = b.orchestration_id
  AND b.started_at IS NULL;

ALTER TABLE ab_orchestration_budget ALTER COLUMN started_at SET NOT NULL;

ALTER TABLE ab_orchestration_budget
    ADD CONSTRAINT ab_budget_expiry_order_check CHECK (expires_at > started_at);

CREATE OR REPLACE FUNCTION ab_budget_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.orchestration_id <> OLD.orchestration_id
        OR NEW.max_actions <> OLD.max_actions
        OR NEW.max_attempts <> OLD.max_attempts
        OR NEW.max_elapsed_ms <> OLD.max_elapsed_ms
        OR NEW.started_at <> OLD.started_at
        OR NEW.expires_at <> OLD.expires_at THEN
        RAISE EXCEPTION 'SC-1431 budget limits and timestamps are immutable';
    END IF;
    IF NEW.actions_used < OLD.actions_used OR NEW.attempts_used < OLD.attempts_used THEN
        RAISE EXCEPTION 'SC-1431 budget counters may only advance';
    END IF;
    IF NEW.version <= OLD.version THEN
        RAISE EXCEPTION 'SC-1431 budget version must advance with every update';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ab_budget_guard_trigger
    BEFORE UPDATE ON ab_orchestration_budget
    FOR EACH ROW EXECUTE FUNCTION ab_budget_guard();


-- Canonical source: db/migration/V60__sc1431_bootstrap_provider_identity.sql
-- SC-1431.5: allow null bootstrap prompt provenance and persist provider session/turn identity.
--
-- V48 added observation_digest and prompt_digest as NOT NULL DEFAULT ''. A bootstrap proposal
-- intentionally carries no provider prompt, so prompt_digest must be nullable; the validator still
-- requires a valid prompt digest for provider sources. Provider-bound orchestrations also gain the
-- durable provider session and turn identity so two distinct provider turns with identical decision
-- and provenance content can never resolve as the same exact replay.

ALTER TABLE ab_snapshot_binding
    ALTER COLUMN prompt_digest DROP NOT NULL,
    ALTER COLUMN prompt_digest DROP DEFAULT,
    ADD COLUMN provider_session_id VARCHAR(128),
    ADD COLUMN provider_turn_id VARCHAR(128);

-- Provider identity is all-or-nothing: a bootstrap binding carries neither value, and a provider
-- binding must carry both as bounded identifiers.
ALTER TABLE ab_snapshot_binding
    ADD CONSTRAINT ab_binding_provider_identity_check CHECK (
        (provider_session_id IS NULL AND provider_turn_id IS NULL)
        OR (provider_session_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
            AND provider_turn_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
    );


-- SourceRunBinding current mapping. Historical development rows retain unknown identity.
ALTER TABLE source_run_bindings
    ADD COLUMN repository_id VARCHAR(200),
    ADD COLUMN commit_id VARCHAR(64),
    ADD COLUMN tree_id VARCHAR(64),
    ADD COLUMN build_id VARCHAR(200);
