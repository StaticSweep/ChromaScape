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
