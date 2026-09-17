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
