-- Parent iteration table referenced by the compile-recovery FKs below. It mirrors the core
-- PatchLoopIteration entity columns so the chain is self-consistent on a clean database;
-- Hibernate (ddl-auto=update/create-drop) adds any remaining entity columns at startup.
CREATE TABLE IF NOT EXISTS patch_loop_iteration (
    id BIGSERIAL PRIMARY KEY,
    iteration_id VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(50) NOT NULL,
    script_name VARCHAR(200) NOT NULL,
    prompt TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_state_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS builder_goal_compile_artifact (
    artifact_id VARCHAR(36) PRIMARY KEY,
    iteration_id VARCHAR(100) NOT NULL REFERENCES patch_loop_iteration(iteration_id) ON DELETE CASCADE,
    repair_attempt_id VARCHAR(36),
    artifact_kind VARCHAR(40) NOT NULL,
    storage_key VARCHAR(500) NOT NULL UNIQUE,
    sha256 VARCHAR(64) NOT NULL,
    byte_count BIGINT NOT NULL CHECK (byte_count >= 0),
    line_count BIGINT NOT NULL CHECK (line_count >= 0),
    sanitization_version VARCHAR(20) NOT NULL,
    sanitization_status VARCHAR(20) NOT NULL,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT bg_compile_artifact_kind CHECK (artifact_kind IN
        ('ORIGINAL_COMPILE_LOG', 'VERIFICATION_LOG', 'COMPILER_ERRORS', 'AI_PROMPT'))
);

CREATE TABLE IF NOT EXISTS builder_goal_compile_repair_attempt (
    attempt_id VARCHAR(36) PRIMARY KEY,
    iteration_id VARCHAR(100) NOT NULL REFERENCES patch_loop_iteration(iteration_id) ON DELETE CASCADE,
    source_artifact_id VARCHAR(36) NOT NULL REFERENCES builder_goal_compile_artifact(artifact_id),
    codex_session_id VARCHAR(160) NOT NULL,
    state VARCHAR(40) NOT NULL,
    pass_number INTEGER NOT NULL DEFAULT 0,
    max_passes INTEGER NOT NULL,
    prompt_sha256 VARCHAR(64) NOT NULL,
    patch_id VARCHAR(100),
    patch_revision VARCHAR(100),
    apply_mode VARCHAR(30) NOT NULL,
    approval_state VARCHAR(30) NOT NULL,
    error_count_before INTEGER NOT NULL DEFAULT 0,
    error_count_after INTEGER,
    verification_command_key VARCHAR(80),
    verification_exit_code INTEGER,
    failure_code VARCHAR(100),
    failure_detail VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT bg_compile_attempt_passes CHECK
        (max_passes BETWEEN 1 AND 3 AND pass_number BETWEEN 0 AND max_passes),
    CONSTRAINT bg_compile_attempt_state CHECK (state IN
        ('CAPTURED', 'PROMPT_READY', 'AI_ANALYZING', 'PLAN_READY', 'AWAITING_APPROVAL',
         'APPLYING', 'VERIFYING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

ALTER TABLE builder_goal_compile_artifact
    ADD CONSTRAINT bg_compile_artifact_attempt_fk
    FOREIGN KEY (repair_attempt_id) REFERENCES builder_goal_compile_repair_attempt(attempt_id);

CREATE INDEX IF NOT EXISTS idx_bg_compile_artifact_run_created
    ON builder_goal_compile_artifact(iteration_id, created_at);
CREATE INDEX IF NOT EXISTS idx_bg_compile_attempt_run_created
    ON builder_goal_compile_repair_attempt(iteration_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_bg_compile_active_attempt
    ON builder_goal_compile_repair_attempt(iteration_id)
    WHERE state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED');
