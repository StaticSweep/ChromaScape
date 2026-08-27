-- V15: Align replay schema with JPA mappings and add branch semantics.
-- V11-V14 remain immutable per Flyway policy; this migration repairs gaps:
--   * replay_mismatches.attempt_id (entity requires it, V14 omitted it)
--   * branch lineage columns on replay_runs (branch_key / parent_run_id /
--     parent_checkpoint_id) required for real branch-from-checkpoint runs.
--
-- Backfill sequence matters: the column must be added NULLABLE WITHOUT a
-- default first, backfilled from a same-run attempt, then constrained.
-- A DEFAULT gen_random_uuid() here would fill existing rows with random
-- UUIDs that violate the foreign key added below.

-- V14 used CREATE TABLE IF NOT EXISTS, so databases from the older Hibernate
-- replay prototype can still have an incompatible BIGINT/text mismatch table.
-- Archive that recognized legacy shape and install the V14 UUID shape before
-- applying the attempt alignment below.  The archived rows remain available
-- for an explicit domain-level import; their text run IDs cannot safely be
-- inferred as UUID run identities here.
DO $$
DECLARE
    mismatch_id_type TEXT;
    mismatch_run_id_type TEXT;
    legacy_shape BOOLEAN;
BEGIN
    SELECT data_type
      INTO mismatch_id_type
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'replay_mismatches'
       AND column_name = 'id';

    SELECT data_type
      INTO mismatch_run_id_type
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'replay_mismatches'
       AND column_name = 'run_id';

    IF mismatch_id_type IS NOT NULL
       AND (mismatch_id_type <> 'uuid' OR mismatch_run_id_type <> 'uuid') THEN
        SELECT mismatch_id_type = 'bigint'
               AND mismatch_run_id_type = 'text'
               AND EXISTS (
                   SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'replay_mismatches' AND column_name = 'session_id'
               )
               AND EXISTS (
                   SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = 'replay_mismatches' AND column_name = 'category'
               )
          INTO legacy_shape;

        IF NOT legacy_shape THEN
            RAISE EXCEPTION
                'V15 cannot migrate replay_mismatches: expected UUID schema or recognized legacy BIGINT/text schema, found id type % and run_id type %',
                mismatch_id_type, mismatch_run_id_type;
        END IF;

        IF to_regclass(current_schema() || '.replay_mismatches_legacy_v15') IS NOT NULL THEN
            RAISE EXCEPTION
                'V15 cannot archive legacy replay_mismatches: replay_mismatches_legacy_v15 already exists';
        END IF;

        ALTER TABLE replay_mismatches RENAME TO replay_mismatches_legacy_v15;
        IF to_regclass(current_schema() || '.replay_mismatches_pkey') IS NOT NULL THEN
            ALTER INDEX replay_mismatches_pkey RENAME TO replay_mismatches_legacy_v15_pkey;
        END IF;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS replay_mismatches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES replay_runs(id) ON DELETE CASCADE,
    level VARCHAR NOT NULL CHECK (level IN ('HARD', 'SOFT', 'INFO')),
    rule_id VARCHAR NOT NULL,
    first_tick BIGINT NOT NULL,
    sequence INTEGER NOT NULL DEFAULT 0,
    domain VARCHAR NOT NULL,
    json_pointer VARCHAR,
    expected_value VARCHAR,
    actual_value VARCHAR,
    related_action_ordinal INTEGER,
    checkpoint_id UUID REFERENCES replay_checkpoints(id),
    causal_parent_id UUID,
    cascade_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- 1. Add attempt_id as nullable, no default.
ALTER TABLE replay_mismatches
    ADD COLUMN IF NOT EXISTS attempt_id UUID;

-- 2. Runs that carry mismatches but have no attempt at all (legacy rows)
--    receive one synthetic migration attempt so the FK can be satisfied.
INSERT INTO replay_attempts (id, run_id, attempt_no, fencing_generation, state, created_at, terminal_at)
SELECT gen_random_uuid(),
       m.run_id,
       COALESCE((SELECT MAX(a.attempt_no) + 1 FROM replay_attempts a WHERE a.run_id = m.run_id), 1),
       COALESCE((SELECT MAX(a.fencing_generation) + 1 FROM replay_attempts a WHERE a.run_id = m.run_id), 1),
       'MIGRATED',
       (SELECT MIN(m2.created_at) FROM replay_mismatches m2 WHERE m2.run_id = m.run_id),
       (SELECT MAX(m2.created_at) FROM replay_mismatches m2 WHERE m2.run_id = m.run_id)
FROM (SELECT DISTINCT run_id FROM replay_mismatches) m
WHERE NOT EXISTS (
    SELECT 1 FROM replay_attempts a WHERE a.run_id = m.run_id
);

-- 3. Backfill every null attempt_id from the latest attempt of the same run.
UPDATE replay_mismatches m
SET attempt_id = (
    SELECT a.id FROM replay_attempts a
    WHERE a.run_id = m.run_id
    ORDER BY a.attempt_no DESC
    LIMIT 1
)
WHERE m.attempt_id IS NULL;

-- 4. Abort loudly if any mismatch row still has no attempt; silently
--    continuing would produce an unenforceable foreign key.
DO $$
DECLARE orphan_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO orphan_count FROM replay_mismatches WHERE attempt_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION 'V15 backfill incomplete: % replay_mismatches rows have no resolvable attempt', orphan_count;
    END IF;
END $$;

-- 5. Foreign key to the owning attempt.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_mismatch_attempt'
    ) THEN
        ALTER TABLE replay_mismatches
            ADD CONSTRAINT fk_mismatch_attempt
            FOREIGN KEY (attempt_id) REFERENCES replay_attempts(id) ON DELETE CASCADE;
    END IF;
END $$;

-- 6. Not null now that every row is backed by a real attempt.
ALTER TABLE replay_mismatches ALTER COLUMN attempt_id DROP DEFAULT;
ALTER TABLE replay_mismatches ALTER COLUMN attempt_id SET NOT NULL;

-- 7. Lookup index.
CREATE INDEX IF NOT EXISTS idx_replay_mismatches_attempt ON replay_mismatches(attempt_id);

-- Branch lineage for runs created from a checkpoint.
ALTER TABLE replay_runs ADD COLUMN IF NOT EXISTS branch_key VARCHAR NOT NULL DEFAULT 'main';
ALTER TABLE replay_runs ADD COLUMN IF NOT EXISTS parent_run_id UUID REFERENCES replay_runs(id) ON DELETE SET NULL;
ALTER TABLE replay_runs ADD COLUMN IF NOT EXISTS parent_checkpoint_id UUID;

CREATE INDEX IF NOT EXISTS idx_replay_runs_parent ON replay_runs(parent_run_id);
