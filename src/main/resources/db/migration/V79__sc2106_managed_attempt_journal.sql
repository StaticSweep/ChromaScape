-- SC-2106 R5.1: an attempt's SC-1916 journal in managed storage. Segment bytes live in the
-- immutable artifact store (content-addressed); these rows are the owner's committed references to
-- them and the managed head a recovery restores from. The owner uploads bytes first, then commits
-- the references and advances the head under its fence, by compare-and-set on the previous head, in
-- one transaction. An uploaded segment without a committed row is not part of the journal.
CREATE TABLE candidate_attempt_journal_heads (
    attempt_id   TEXT PRIMARY KEY REFERENCES candidate_execution_attempts (attempt_id),
    sequence     BIGINT NOT NULL CHECK (sequence >= 1),
    chain_digest TEXT NOT NULL CHECK (chain_digest ~ '^sha256:[0-9a-f]{64}$'),
    event_count  BIGINT NOT NULL CHECK (event_count >= 1),
    state        TEXT NOT NULL CHECK (state IN ('OPEN', 'TERMINAL')),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE candidate_attempt_journal_segments (
    attempt_id            TEXT NOT NULL REFERENCES candidate_execution_attempts (attempt_id),
    sequence              BIGINT NOT NULL CHECK (sequence >= 1),
    segment_digest        TEXT NOT NULL CHECK (segment_digest ~ '^sha256:[0-9a-f]{64}$'),
    previous_chain_digest TEXT NOT NULL CHECK (previous_chain_digest ~ '^sha256:[0-9a-f]{64}$'),
    chain_digest          TEXT NOT NULL CHECK (chain_digest ~ '^sha256:[0-9a-f]{64}$'),
    event_count           INT NOT NULL CHECK (event_count >= 1),
    artifact_id           TEXT NOT NULL,
    owner_lease_id        TEXT NOT NULL,
    owner_epoch           BIGINT NOT NULL,
    committed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (attempt_id, sequence)
);
