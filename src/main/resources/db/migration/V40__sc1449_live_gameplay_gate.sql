-- SC-1449: durable SC-1429/v2 live gameplay gate storage. Replaces the in-memory
-- idempotency, authority, request-row and lease-slot maps so reservation, authority
-- consumption and reconciliation survive a ChromaScape restart.

CREATE TABLE gameplay_v2_gate_request (
    request_id       VARCHAR(128) PRIMARY KEY,
    idempotency_key  VARCHAR(200) NOT NULL,
    fingerprint      VARCHAR(128) NOT NULL,
    envelope_digest  VARCHAR(128) NOT NULL,
    authority_id     VARCHAR(128) NOT NULL,
    account_id       VARCHAR(128) NOT NULL,
    client_id        VARCHAR(128) NOT NULL,
    lease_generation BIGINT       NOT NULL,
    fence            BIGINT       NOT NULL,
    state            VARCHAR(32)  NOT NULL,
    failure_code     VARCHAR(64),
    acknowledged     BOOLEAN      NOT NULL DEFAULT FALSE,
    request_bytes    BYTEA        NOT NULL,
    receipt_json     TEXT,
    evidence_refs    TEXT         NOT NULL DEFAULT '',
    entity_version   BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_gameplay_v2_gate_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uq_gameplay_v2_gate_authority UNIQUE (authority_id),
    CONSTRAINT uq_gameplay_v2_gate_slot UNIQUE (account_id, client_id, lease_generation, fence)
);

CREATE INDEX idx_gameplay_v2_gate_state ON gameplay_v2_gate_request (state);
