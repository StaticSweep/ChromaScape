-- SC-1431.3: enforce the budget contract in PostgreSQL and persist full provenance bindings.
--
-- V47 introduced the budget table; this migration hardens it. Claimed limits and timestamps are
-- immutable, counters may only advance, and the optimistic-lock version must advance with every
-- update. The snapshot binding table gains the observation and prompt provenance digests that a
-- bindable provider decision must carry.

ALTER TABLE ab_snapshot_binding
    ADD COLUMN observation_digest VARCHAR(80) NOT NULL DEFAULT '',
    ADD COLUMN prompt_digest VARCHAR(80) NOT NULL DEFAULT '';

-- started_at is required by the Java model; backfill any candidate rows from the orchestration
-- root before making the column non-null.
UPDATE ab_orchestration_budget b
SET started_at = o.created_at
FROM ab_orchestration o
WHERE o.orchestration_id = b.orchestration_id
  AND b.started_at IS NULL;

ALTER TABLE ab_orchestration_budget ALTER COLUMN started_at SET NOT NULL;

ALTER TABLE ab_orchestration_budget
    ADD CONSTRAINT ab_budget_expiry_order_check CHECK (expires_at > started_at);

CREATE OR REPLACE FUNCTION ab_budget_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.orchestration_id <> OLD.orchestration_id
        OR NEW.max_actions <> OLD.max_actions
        OR NEW.max_attempts <> OLD.max_attempts
        OR NEW.max_elapsed_ms <> OLD.max_elapsed_ms
        OR NEW.started_at <> OLD.started_at
        OR NEW.expires_at <> OLD.expires_at THEN
        RAISE EXCEPTION 'SC-1431 budget limits and timestamps are immutable';
    END IF;
    IF NEW.actions_used < OLD.actions_used OR NEW.attempts_used < OLD.attempts_used THEN
        RAISE EXCEPTION 'SC-1431 budget counters may only advance';
    END IF;
    IF NEW.version <= OLD.version THEN
        RAISE EXCEPTION 'SC-1431 budget version must advance with every update';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ab_budget_guard_trigger
    BEFORE UPDATE ON ab_orchestration_budget
    FOR EACH ROW EXECUTE FUNCTION ab_budget_guard();
