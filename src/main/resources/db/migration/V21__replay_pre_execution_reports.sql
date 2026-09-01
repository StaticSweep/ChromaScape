-- V21: support immutable reports for runs cancelled before execution.
-- A QUEUED run has no attempt yet; its mandatory terminal report therefore carries a NULL
-- attempt_id (and NULL runtime identity). Executed-run reports keep requiring non-null values,
-- enforced by the report-v1 contract and application validation, not by this migration.
ALTER TABLE replay_reports
    ALTER COLUMN attempt_id DROP NOT NULL;
