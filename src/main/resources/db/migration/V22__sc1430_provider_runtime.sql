CREATE TABLE gameplay_provider_session (
    session_id VARCHAR(160) PRIMARY KEY,
    provider VARCHAR(16) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    state VARCHAR(32) NOT NULL,
    task_id VARCHAR(160) NOT NULL,
    run_id VARCHAR(160) NOT NULL,
    recording_id VARCHAR(160) NOT NULL,
    prompt_digest VARCHAR(71) NOT NULL,
    toolset_digest VARCHAR(71) NOT NULL,
    knowledge_digest VARCHAR(71) NOT NULL,
    goal_plan_digest VARCHAR(71) NOT NULL,
    request_json TEXT NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    decide_only BOOLEAN NOT NULL CHECK (decide_only),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE gameplay_provider_budget_ledger (
    session_id VARCHAR(160) PRIMARY KEY REFERENCES gameplay_provider_session(session_id),
    input_tokens_used BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens_used >= 0),
    output_tokens_used BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens_used >= 0),
    provider_calls_used INTEGER NOT NULL DEFAULT 0 CHECK (provider_calls_used >= 0),
    tool_calls_used INTEGER NOT NULL DEFAULT 0 CHECK (tool_calls_used >= 0),
    turns_used INTEGER NOT NULL DEFAULT 0 CHECK (turns_used >= 0),
    elapsed_millis BIGINT NOT NULL DEFAULT 0 CHECK (elapsed_millis >= 0),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE gameplay_provider_turn (
    turn_id VARCHAR(160) PRIMARY KEY,
    session_id VARCHAR(160) NOT NULL REFERENCES gameplay_provider_session(session_id),
    request_id VARCHAR(160) NOT NULL UNIQUE,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(71) NOT NULL,
    state VARCHAR(32) NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    provider_session_ref VARCHAR(160),
    provider_turn_ref VARCHAR(160),
    observation_digest VARCHAR(71) NOT NULL,
    eligibility_digest VARCHAR(71) NOT NULL,
    decision_digest VARCHAR(71),
    semantic_action_digest VARCHAR(71),
    request_json TEXT NOT NULL,
    usage_json TEXT,
    transcript_ref VARCHAR(160),
    deadline TIMESTAMPTZ NOT NULL,
    decide_only BOOLEAN NOT NULL CHECK (decide_only),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_gameplay_provider_turn_session ON gameplay_provider_turn(session_id, created_at);
CREATE INDEX idx_gameplay_provider_turn_recovery ON gameplay_provider_turn(state, updated_at);

CREATE TABLE gameplay_provider_event (
    event_id VARCHAR(160) PRIMARY KEY,
    turn_id VARCHAR(160) NOT NULL REFERENCES gameplay_provider_turn(turn_id),
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_digest VARCHAR(71) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (turn_id, sequence_no)
);

CREATE TABLE gameplay_provider_tool_call (
    tool_call_id VARCHAR(160) PRIMARY KEY,
    turn_id VARCHAR(160) NOT NULL REFERENCES gameplay_provider_turn(turn_id),
    tool_name VARCHAR(128) NOT NULL,
    arguments_digest VARCHAR(71) NOT NULL,
    result_digest VARCHAR(71),
    state VARCHAR(32) NOT NULL,
    charged_calls INTEGER NOT NULL CHECK (charged_calls >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE gameplay_provider_decision (
    decision_id VARCHAR(160) PRIMARY KEY,
    turn_id VARCHAR(160) NOT NULL UNIQUE REFERENCES gameplay_provider_turn(turn_id),
    contract_version VARCHAR(32) NOT NULL,
    decision_digest VARCHAR(71) NOT NULL,
    semantic_action_digest VARCHAR(71) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    decision_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE gameplay_provider_transcript_artifact (
    artifact_id VARCHAR(160) PRIMARY KEY,
    turn_id VARCHAR(160) NOT NULL UNIQUE REFERENCES gameplay_provider_turn(turn_id),
    sha256 VARCHAR(71) NOT NULL,
    redaction_version VARCHAR(32) NOT NULL,
    byte_length BIGINT NOT NULL CHECK (byte_length >= 0),
    created_at TIMESTAMPTZ NOT NULL
);
