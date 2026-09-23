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
