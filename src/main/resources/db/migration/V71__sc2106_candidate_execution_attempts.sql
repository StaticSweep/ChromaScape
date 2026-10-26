-- SC-2106 R4: one row per candidate execution attempt; the row lock is the commit boundary.
-- (owner_id, idempotency_key) maps to exactly one attempt. Every mutation after the reservation
-- is fenced on (lease_id, epoch); reconciliation bumps the epoch so a stale owner can never write,
-- even when it still holds the same lease id.
CREATE TABLE candidate_execution_attempts (
    attempt_id       TEXT PRIMARY KEY,
    owner_id         TEXT NOT NULL,
    idempotency_key  TEXT NOT NULL,
    job_id           TEXT NOT NULL,
    fingerprint      TEXT NOT NULL,
    state            TEXT NOT NULL CHECK (state IN
                         ('RESERVED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'UNCERTAIN')),
    lease_id         TEXT,
    epoch            BIGINT NOT NULL CHECK (epoch >= 1),
    lease_expires_at TIMESTAMPTZ,
    request_digest   TEXT,
    job              JSONB,
    detail           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT candidate_execution_attempts_owner_key UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX candidate_execution_attempts_open
    ON candidate_execution_attempts (lease_expires_at)
    WHERE state IN ('RESERVED', 'RUNNING');
