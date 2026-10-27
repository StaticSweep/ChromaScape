-- SC-2106 R6: database defence for immediate siblings. The repository additionally locks the
-- original attempt and checks its whole recovery lineage before reserving any continuation.
-- At most one immediate recovery of an attempt may be
-- live (RESERVED, RUNNING), finished (COMPLETED) or itself uncertain (UNCERTAIN) at any time, so two
-- callers can never both continue the same attempt. A recovery that ended FAILED or CANCELLED
-- releases the claim and the attempt may be recovered again; an UNCERTAIN recovery is recovered in
-- its own right instead. (V72 is taken by an unmerged branch.)
ALTER TABLE candidate_execution_attempts
    ADD COLUMN recovers TEXT REFERENCES candidate_execution_attempts (attempt_id);

CREATE UNIQUE INDEX candidate_execution_attempts_one_recovery
    ON candidate_execution_attempts (recovers)
    WHERE recovers IS NOT NULL AND state IN ('RESERVED', 'RUNNING', 'COMPLETED', 'UNCERTAIN');
