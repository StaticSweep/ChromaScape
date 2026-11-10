-- SC-1498: durable SC-1429/v2 bootstrap draft.
--
-- The frozen bundle bytes and every digest are written once and never updated: they are the
-- substrate the human approves and the gateway transmits. A changed draft is a new row, never an
-- update, so an approval can never be re-pointed at different bytes.

CREATE TABLE IF NOT EXISTS gameplay_bootstrap_v2_draft (
    draft_id VARCHAR(128) PRIMARY KEY,
    provider_session_id VARCHAR(160) NOT NULL,
    provider_turn_id VARCHAR(160) NOT NULL,
    request_id VARCHAR(128) NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    recording_id VARCHAR(160) NOT NULL,
    authority_id VARCHAR(128) NOT NULL,
    bundle_bytes TEXT NOT NULL,
    bundle_digest VARCHAR(80) NOT NULL UNIQUE,
    envelope_digest VARCHAR(80) NOT NULL,
    scope_digest VARCHAR(80) NOT NULL UNIQUE,
    catalog_digest VARCHAR(80) NOT NULL,
    observation_digest VARCHAR(80) NOT NULL,
    planning_input_digest VARCHAR(80) NOT NULL,
    eligibility_digest VARCHAR(80) NOT NULL,
    goal_plan_digest VARCHAR(80) NOT NULL,
    semantic_action_digest VARCHAR(80) NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT gameplay_bootstrap_v2_draft_digest_check
        CHECK (bundle_digest ~ '^sha256:[0-9a-f]{64}$'
            AND envelope_digest ~ '^sha256:[0-9a-f]{64}$'
            AND scope_digest ~ '^sha256:[0-9a-f]{64}$'
            AND catalog_digest ~ '^sha256:[0-9a-f]{64}$'
            AND observation_digest ~ '^sha256:[0-9a-f]{64}$'
            AND planning_input_digest ~ '^sha256:[0-9a-f]{64}$'
            AND eligibility_digest ~ '^sha256:[0-9a-f]{64}$'
            AND goal_plan_digest ~ '^sha256:[0-9a-f]{64}$'
            AND semantic_action_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE OR REPLACE FUNCTION sc1498_reject_v2_draft_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'SC-1498 v2 bootstrap draft % is immutable', OLD.draft_id
        USING ERRCODE = '55006';
END;
$$;

DROP TRIGGER IF EXISTS sc1498_v2_draft_no_update ON gameplay_bootstrap_v2_draft;
CREATE TRIGGER sc1498_v2_draft_no_update
    BEFORE UPDATE ON gameplay_bootstrap_v2_draft
    FOR EACH ROW EXECUTE FUNCTION sc1498_reject_v2_draft_mutation();

DROP TRIGGER IF EXISTS sc1498_v2_draft_no_delete ON gameplay_bootstrap_v2_draft;
CREATE TRIGGER sc1498_v2_draft_no_delete
    BEFORE DELETE ON gameplay_bootstrap_v2_draft
    FOR EACH ROW EXECUTE FUNCTION sc1498_reject_v2_draft_mutation();
