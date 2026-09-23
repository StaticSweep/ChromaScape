-- SC-1431.2: durable lifecycle budget counters for bounded AccountBuilder orchestration.
-- Limits are immutable once claimed; only the used counters advance through atomic conditional
-- updates. No authority signature, key, token secret or raw fence token is stored here.

CREATE TABLE ab_orchestration_budget (
    orchestration_id VARCHAR(128) PRIMARY KEY
        REFERENCES ab_orchestration(orchestration_id) ON DELETE RESTRICT,
    max_actions INTEGER NOT NULL,
    max_attempts INTEGER NOT NULL,
    max_elapsed_ms BIGINT NOT NULL,
    actions_used INTEGER NOT NULL DEFAULT 0,
    attempts_used INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ab_budget_actions_limit_check CHECK (max_actions BETWEEN 1 AND 20),
    CONSTRAINT ab_budget_attempts_limit_check CHECK (max_attempts BETWEEN 1 AND 3),
    CONSTRAINT ab_budget_elapsed_limit_check CHECK (max_elapsed_ms BETWEEN 1000 AND 1800000),
    CONSTRAINT ab_budget_actions_used_check CHECK (actions_used >= 0 AND actions_used <= max_actions),
    CONSTRAINT ab_budget_attempts_used_check
        CHECK (attempts_used >= 0 AND attempts_used <= max_attempts),
    CONSTRAINT ab_budget_version_check CHECK (version >= 0)
);
