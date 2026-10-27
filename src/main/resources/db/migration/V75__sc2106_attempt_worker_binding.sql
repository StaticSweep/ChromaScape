-- SC-2106 R4.2: the worker session an attempt is bound to, accepted by the attempt's owner before
-- the runner performs any effect. One row per accepted binding generation; the runner reports a
-- new generation whenever the worker's epoch and lease rotate (a restore). binding_generation on
-- the attempt is the current one, advanced in the same owner-fenced transaction that inserts the
-- row, so a skipped, repeated or stale generation can never be accepted. Only a digest of the
-- worker lease is stored. Attempts from before this migration keep generation 0: unbound, never
-- backfilled from paths, PIDs or revisions. (V72 is taken by an unmerged branch.)
ALTER TABLE candidate_execution_attempts
    ADD COLUMN binding_generation BIGINT NOT NULL DEFAULT 0 CHECK (binding_generation >= 0);

CREATE TABLE candidate_attempt_worker_bindings (
    attempt_id          TEXT NOT NULL REFERENCES candidate_execution_attempts (attempt_id),
    generation          BIGINT NOT NULL CHECK (generation >= 1),
    binding_id          TEXT NOT NULL,
    request_digest      TEXT NOT NULL,
    worker_authority    TEXT NOT NULL,
    worker_session_id   TEXT NOT NULL,
    worker_epoch        BIGINT NOT NULL CHECK (worker_epoch >= 0),
    worker_lease_digest TEXT NOT NULL,
    worker_revision     BIGINT NOT NULL CHECK (worker_revision >= 0),
    runner_steps        BIGINT NOT NULL CHECK (runner_steps >= 0),
    runner_intents      BIGINT NOT NULL CHECK (runner_intents >= 0),
    reason              TEXT NOT NULL CHECK (reason IN ('CREATE', 'RESTORE')),
    worker_identity     JSONB,
    owner_lease_id      TEXT NOT NULL,
    owner_epoch         BIGINT NOT NULL,
    accepted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (attempt_id, generation),
    CONSTRAINT candidate_attempt_worker_bindings_id UNIQUE (binding_id)
);
