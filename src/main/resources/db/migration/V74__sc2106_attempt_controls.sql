-- SC-2106 R9.2/R9.5: durable operator controls on a candidate execution attempt. A request is
-- recorded here by whichever process receives it; the process that owns the attempt reads it on
-- its next lease tick and steers the run. cancel_requested and pause_requested are what was asked;
-- paused is what the owner observed (the runner actually parked), written under the owner's fence.
-- pause_requested survives the attempt, so a recovery of a paused attempt starts paused.
ALTER TABLE candidate_execution_attempts
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN pause_requested  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN paused           BOOLEAN NOT NULL DEFAULT FALSE;
