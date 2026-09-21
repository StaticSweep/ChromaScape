-- SC-810: make a builder-goal run's source-binding outcome explicit and queryable.
--
-- source_run_bindings (V21) already models the run -> signed-snapshot binding exactly, but the
-- builder-goal dispatch path never wrote one, so a dispatched run carried no durable record of
-- which source snapshot or artifact it executed against. The binding row is the authoritative
-- record; this column exists so the *absence* of a binding is never silently indistinguishable
-- from a run that was simply never checked.
--
-- Values:
--   BOUND          - a verified source_run_bindings row exists for this run.
--   NOT_CONFIGURED - source trust is not configured on this instance, so no binding was attempted.
--   FAILED         - binding was required and attempted, but did not complete.
-- NULL means the run predates this column (historical rows are deliberately not backfilled: no
-- binding data exists for them, and inventing one would be fabricated provenance).

ALTER TABLE patch_loop_iteration
    ADD COLUMN IF NOT EXISTS source_binding_state VARCHAR(40);

ALTER TABLE patch_loop_iteration
    DROP CONSTRAINT IF EXISTS ck_patch_loop_iteration_source_binding_state;

ALTER TABLE patch_loop_iteration
    ADD CONSTRAINT ck_patch_loop_iteration_source_binding_state
        CHECK (source_binding_state IS NULL
               OR source_binding_state IN ('BOUND', 'NOT_CONFIGURED', 'FAILED'));

CREATE INDEX IF NOT EXISTS ix_patch_loop_iteration_source_binding_state
    ON patch_loop_iteration (source_binding_state);
