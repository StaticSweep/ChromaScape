ALTER TABLE builder_goal_compile_repair_attempt
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_bg_compile_attempt_idempotency
    ON builder_goal_compile_repair_attempt(iteration_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE builder_goal_compile_artifact
    ADD CONSTRAINT uq_bg_compile_artifact_run_identity UNIQUE (artifact_id, iteration_id);

ALTER TABLE builder_goal_compile_repair_attempt
    ADD CONSTRAINT uq_bg_compile_attempt_run_identity UNIQUE (attempt_id, iteration_id);

ALTER TABLE builder_goal_compile_repair_attempt
    DROP CONSTRAINT IF EXISTS bg_compile_attempt_source_fk_legacy;

-- V9 originally declared this reference inline, so PostgreSQL generated the
-- constraint name below. Accept the briefly used explicit name above as well
-- to keep local databases created from either development revision repairable.
ALTER TABLE builder_goal_compile_repair_attempt
    DROP CONSTRAINT IF EXISTS builder_goal_compile_repair_attempt_source_artifact_id_fkey;

ALTER TABLE builder_goal_compile_repair_attempt
    ADD CONSTRAINT bg_compile_attempt_source_fk
    FOREIGN KEY (source_artifact_id, iteration_id)
    REFERENCES builder_goal_compile_artifact(artifact_id, iteration_id);

ALTER TABLE builder_goal_compile_artifact
    DROP CONSTRAINT bg_compile_artifact_attempt_fk;

ALTER TABLE builder_goal_compile_artifact
    ADD CONSTRAINT bg_compile_artifact_attempt_fk
    FOREIGN KEY (repair_attempt_id, iteration_id)
    REFERENCES builder_goal_compile_repair_attempt(attempt_id, iteration_id);
