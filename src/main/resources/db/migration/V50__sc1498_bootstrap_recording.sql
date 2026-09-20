-- SC-1498: durable human approval and bootstrap recording evidence.
-- The bootstrap layer owns only proposal/approval/recording projection. Delivery state
-- remains owned by SC-1431 (ab_*) and the Micro6 v2 gate. These tables reference and
-- digest that evidence; they never copy leases, receipts or terminal rows.

CREATE TABLE gameplay_bootstrap_approval (
    approval_id VARCHAR(128) PRIMARY KEY,
    scope_digest VARCHAR(80) NOT NULL UNIQUE,
    provider_session_id VARCHAR(160) NOT NULL,
    provider_turn_id VARCHAR(160) NOT NULL,
    orchestration_id VARCHAR(128) NOT NULL UNIQUE,
    request_id VARCHAR(128) NOT NULL UNIQUE,
    recording_id VARCHAR(160) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(80) NOT NULL,
    approved_by VARCHAR(128) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(16) NOT NULL,
    bound_authority_id VARCHAR(128),
    signature_digest VARCHAR(80),
    entity_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT gameplay_bootstrap_approval_state_check
        CHECK (state IN ('APPROVED', 'BOUND', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT gameplay_bootstrap_approval_scope_digest_check
        CHECK (scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT gameplay_bootstrap_approval_fingerprint_check
        CHECK (request_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT gameplay_bootstrap_approval_operator_check
        CHECK (approved_by ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT gameplay_bootstrap_approval_expiry_check
        CHECK (expires_at > approved_at),
    CONSTRAINT gameplay_bootstrap_approval_signature_digest_check
        CHECK (signature_digest IS NULL OR signature_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT gameplay_bootstrap_approval_bound_check
        CHECK (
            (state = 'BOUND'
                AND bound_authority_id IS NOT NULL
                AND signature_digest ~ '^sha256:[0-9a-f]{64}$')
            OR (state IN ('APPROVED', 'CANCELLED', 'EXPIRED')
                AND bound_authority_id IS NULL
                AND signature_digest IS NULL)
        )
);

CREATE INDEX idx_bootstrap_approval_orchestration
    ON gameplay_bootstrap_approval(orchestration_id);
CREATE INDEX idx_bootstrap_approval_state_expiry
    ON gameplay_bootstrap_approval(state, expires_at);
CREATE INDEX idx_bootstrap_approval_provider_turn
    ON gameplay_bootstrap_approval(provider_session_id, provider_turn_id);

CREATE TABLE gameplay_bootstrap_recording (
    orchestration_id VARCHAR(128) PRIMARY KEY,
    recording_id VARCHAR(160) NOT NULL UNIQUE,
    provider_session_id VARCHAR(160) NOT NULL,
    provider_turn_id VARCHAR(160) NOT NULL,
    decision_digest VARCHAR(80) NOT NULL,
    envelope_digest VARCHAR(80) NOT NULL,
    provider VARCHAR(16) NOT NULL,
    model_version VARCHAR(160) NOT NULL,
    manifest_state VARCHAR(16) NOT NULL,
    terminal_outcome VARCHAR(32),
    stable_code VARCHAR(64),
    manifest_digest VARCHAR(80),
    artifact_ref VARCHAR(500),
    artifact_digest VARCHAR(80),
    learned_model_activated BOOLEAN NOT NULL DEFAULT FALSE,
    continuing_authority BOOLEAN NOT NULL DEFAULT FALSE,
    simulator_trace BOOLEAN NOT NULL DEFAULT FALSE,
    entity_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    finalized_at TIMESTAMPTZ,
    CONSTRAINT gameplay_bootstrap_recording_digest_check CHECK (
        decision_digest ~ '^sha256:[0-9a-f]{64}$'
        AND envelope_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT gameplay_bootstrap_recording_manifest_check CHECK (
        manifest_state IN ('STARTED', 'FINALIZED')
    ),
    CONSTRAINT gameplay_bootstrap_recording_manifest_digest_check CHECK (
        (manifest_state = 'FINALIZED' AND manifest_digest ~ '^sha256:[0-9a-f]{64}$')
        OR (manifest_state = 'STARTED' AND manifest_digest IS NULL)
    ),
    CONSTRAINT gameplay_bootstrap_recording_artifact_digest_check CHECK (
        artifact_digest IS NULL OR artifact_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT gameplay_bootstrap_recording_safety_check CHECK (
        learned_model_activated = FALSE
        AND continuing_authority = FALSE
        AND simulator_trace = FALSE
    ),
    CONSTRAINT gameplay_bootstrap_recording_finalized_check CHECK (
        (manifest_state = 'FINALIZED' AND finalized_at IS NOT NULL AND terminal_outcome IS NOT NULL)
        OR (manifest_state = 'STARTED' AND finalized_at IS NULL)
    )
);

CREATE INDEX idx_bootstrap_recording_provider_turn
    ON gameplay_bootstrap_recording(provider_session_id, provider_turn_id);

-- Transition rules enforced by PostgreSQL itself, not just the JPA coordinator:
--   * gameplay_bootstrap_recording permits exactly one transition STARTED -> FINALIZED.
--     Every later UPDATE of a FINALIZED row is rejected, as is any rewrite of a STARTED row
--     that changes its identity evidence or moves it anywhere other than FINALIZED.
--   * gameplay_bootstrap_approval permits exactly one APPROVED -> BOUND/CANCELLED/EXPIRED
--     transition; a row that has left APPROVED can never be mutated again.
--   * DELETE is rejected on both tables unconditionally.
CREATE OR REPLACE FUNCTION sc1498_reject_bootstrap_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'SC-1498 bootstrap evidence table % is append-only', TG_TABLE_NAME
        USING ERRCODE = '55006';
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION sc1498_guard_recording_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Terminal rows are immutable: no field, including the transition target, may change.
    IF OLD.manifest_state = 'FINALIZED' THEN
        RAISE EXCEPTION 'SC-1498 bootstrap recording % is FINALIZED and immutable',
            OLD.orchestration_id USING ERRCODE = '55006';
    END IF;
    -- The only permitted transition is STARTED -> FINALIZED with the full terminal payload.
    IF NEW.manifest_state IS DISTINCT FROM 'FINALIZED'
        OR NEW.terminal_outcome IS NULL
        OR NEW.manifest_digest IS NULL
        OR NEW.finalized_at IS NULL
        OR NEW.manifest_digest !~ '^sha256:[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'SC-1498 bootstrap recording % only permits STARTED -> FINALIZED',
            OLD.orchestration_id USING ERRCODE = '55006';
    END IF;
    -- The identity evidence that was durable at STARTED can never be rewritten afterwards.
    IF NEW.orchestration_id IS DISTINCT FROM OLD.orchestration_id
        OR NEW.recording_id IS DISTINCT FROM OLD.recording_id
        OR NEW.provider_session_id IS DISTINCT FROM OLD.provider_session_id
        OR NEW.provider_turn_id IS DISTINCT FROM OLD.provider_turn_id
        OR NEW.decision_digest IS DISTINCT FROM OLD.decision_digest
        OR NEW.envelope_digest IS DISTINCT FROM OLD.envelope_digest
        OR NEW.provider IS DISTINCT FROM OLD.provider
        OR NEW.model_version IS DISTINCT FROM OLD.model_version THEN
        RAISE EXCEPTION 'SC-1498 bootstrap recording % identity is immutable',
            OLD.orchestration_id USING ERRCODE = '55006';
    END IF;
    -- Terminal evidence gate: the recording starts before SC-1431 delivery, so the STARTED row
    -- may exist without a parent orchestration. FINALIZED, however, is only permitted once the
    -- delivered SC-1431 orchestration and its durable terminal result both exist for this id.
    IF NOT EXISTS (
            SELECT 1 FROM ab_orchestration o
            WHERE o.orchestration_id = OLD.orchestration_id)
        OR NOT EXISTS (
            SELECT 1 FROM ab_orchestration_terminal_result t
            WHERE t.orchestration_id = OLD.orchestration_id) THEN
        RAISE EXCEPTION
            'SC-1498 bootstrap recording % cannot finalize without terminal SC-1431 evidence',
            OLD.orchestration_id USING ERRCODE = '55006';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION sc1498_guard_approval_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.state <> 'APPROVED' THEN
        RAISE EXCEPTION 'SC-1498 bootstrap approval % already left APPROVED and is immutable',
            OLD.approval_id USING ERRCODE = '55006';
    END IF;
    IF NEW.state NOT IN ('BOUND', 'CANCELLED', 'EXPIRED') THEN
        RAISE EXCEPTION 'SC-1498 bootstrap approval % only permits APPROVED -> BOUND/CANCELLED/EXPIRED',
            OLD.approval_id USING ERRCODE = '55006';
    END IF;
    -- The approval identity is immutable: a direct SQL APPROVED -> BOUND update must not
    -- rewrite the approved scope, request identity, operator identity, expiry or any other
    -- protected field. Only the transition target, the two BOUND evidence fields where
    -- appropriate, entity_version and updated_at may change.
    IF NEW.approval_id IS DISTINCT FROM OLD.approval_id
        OR NEW.scope_digest IS DISTINCT FROM OLD.scope_digest
        OR NEW.provider_session_id IS DISTINCT FROM OLD.provider_session_id
        OR NEW.provider_turn_id IS DISTINCT FROM OLD.provider_turn_id
        OR NEW.orchestration_id IS DISTINCT FROM OLD.orchestration_id
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.recording_id IS DISTINCT FROM OLD.recording_id
        OR NEW.request_fingerprint IS DISTINCT FROM OLD.request_fingerprint
        OR NEW.approved_by IS DISTINCT FROM OLD.approved_by
        OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
        OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'SC-1498 bootstrap approval % identity is immutable',
            OLD.approval_id USING ERRCODE = '55006';
    END IF;
    -- BOUND must attach both authority evidence fields; leaving APPROVED any other way
    -- must attach neither, so the evidence cannot be smuggled through CANCELLED/EXPIRED
    -- or omitted from BOUND by direct SQL.
    IF NEW.state = 'BOUND' THEN
        IF NEW.bound_authority_id IS NULL
            OR NEW.signature_digest IS NULL
            OR NEW.signature_digest !~ '^sha256:[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'SC-1498 bootstrap approval % BOUND requires authority evidence',
                OLD.approval_id USING ERRCODE = '55006';
        END IF;
    ELSIF NEW.bound_authority_id IS NOT NULL OR NEW.signature_digest IS NOT NULL THEN
        RAISE EXCEPTION 'SC-1498 bootstrap approval % only BOUND carries authority evidence',
            OLD.approval_id USING ERRCODE = '55006';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS sc1498_bootstrap_recording_no_delete ON gameplay_bootstrap_recording;
CREATE TRIGGER sc1498_bootstrap_recording_no_delete
    BEFORE DELETE ON gameplay_bootstrap_recording
    FOR EACH ROW EXECUTE FUNCTION sc1498_reject_bootstrap_mutation();

DROP TRIGGER IF EXISTS sc1498_bootstrap_recording_started_to_finalized_only ON gameplay_bootstrap_recording;
CREATE TRIGGER sc1498_bootstrap_recording_started_to_finalized_only
    BEFORE UPDATE ON gameplay_bootstrap_recording
    FOR EACH ROW EXECUTE FUNCTION sc1498_guard_recording_update();

DROP TRIGGER IF EXISTS sc1498_bootstrap_approval_no_delete ON gameplay_bootstrap_approval;
CREATE TRIGGER sc1498_bootstrap_approval_no_delete
    BEFORE DELETE ON gameplay_bootstrap_approval
    FOR EACH ROW EXECUTE FUNCTION sc1498_reject_bootstrap_mutation();

DROP TRIGGER IF EXISTS sc1498_bootstrap_approval_approved_once ON gameplay_bootstrap_approval;
CREATE TRIGGER sc1498_bootstrap_approval_approved_once
    BEFORE UPDATE ON gameplay_bootstrap_approval
    FOR EACH ROW EXECUTE FUNCTION sc1498_guard_approval_update();
