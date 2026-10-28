-- SC-2106 P3 (R4.3): the owner records each worker command's exact identity before the runner may
-- send it. A row is PERMITTED once the owner commits it, under the owner's fence and the attempt's
-- current worker binding; the runner sends the call only after that. The receipt moves it to
-- APPLIED or REJECTED, or UNKNOWN when the worker's answer was lost. Reconciling an expired attempt
-- turns a still-PERMITTED row into UNKNOWN evidence, as does finishing an attempt with one open; it
-- is never retried under a new command id. A recovery resolves its source attempt's PERMITTED or
-- UNKNOWN commands from the cut it restored, atomically with accepting its RESTORE binding: APPLIED
-- when the cut's worker had accepted the command, SUPERSEDED when the restored world predates it.
-- At most one command per attempt is open at a time. (V76/V77 are taken by
-- codex/live-fishing-verification-20261027.)
CREATE TABLE candidate_attempt_commands (
    attempt_id               TEXT NOT NULL REFERENCES candidate_execution_attempts (attempt_id),
    command_id               TEXT NOT NULL,
    intent_id                TEXT NOT NULL,
    method                   TEXT NOT NULL CHECK (method IN (
        'session.candidate.execute', 'session.candidate.advance',
        'session.candidate.restore', 'session.candidate.resume')),
    kind                     TEXT NOT NULL CHECK (kind IN ('EFFECT', 'TIME', 'LIFECYCLE')),
    payload_digest           TEXT NOT NULL CHECK (payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    binding_generation       BIGINT NOT NULL CHECK (binding_generation >= 1),
    expected_worker_epoch    BIGINT NOT NULL CHECK (expected_worker_epoch >= 0),
    expected_worker_revision BIGINT NOT NULL CHECK (expected_worker_revision >= 0),
    state                    TEXT NOT NULL
        CHECK (state IN ('PERMITTED', 'APPLIED', 'REJECTED', 'UNKNOWN', 'SUPERSEDED')),
    worker_revision          BIGINT CHECK (worker_revision >= 0),
    receipt_digest           TEXT,
    owner_lease_id           TEXT NOT NULL,
    owner_epoch              BIGINT NOT NULL,
    permitted_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at              TIMESTAMPTZ,
    resolved_by_attempt_id   TEXT REFERENCES candidate_execution_attempts (attempt_id),
    resolution_evidence      JSONB,
    PRIMARY KEY (attempt_id, command_id),
    CONSTRAINT candidate_attempt_commands_intent UNIQUE (intent_id),
    CONSTRAINT candidate_attempt_commands_resolved_by CHECK (
        (resolved_by_attempt_id IS NULL) = (resolution_evidence IS NULL)
        AND (state <> 'SUPERSEDED' OR resolved_by_attempt_id IS NOT NULL))
);

CREATE UNIQUE INDEX candidate_attempt_commands_one_open
    ON candidate_attempt_commands (attempt_id) WHERE state = 'PERMITTED';
