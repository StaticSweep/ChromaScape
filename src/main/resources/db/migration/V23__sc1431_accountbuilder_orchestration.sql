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
