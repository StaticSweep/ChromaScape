-- V26: idempotent v2 run request identity and explicit viewer intent.
-- Both fields are additive so existing v1 runs remain readable.

ALTER TABLE replay_runs
    ADD COLUMN IF NOT EXISTS viewer_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(71);
