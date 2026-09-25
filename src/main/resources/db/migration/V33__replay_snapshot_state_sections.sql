-- V33: additive replay snapshot state sections.
-- Existing observed playback remains readable; old rows keep NULL for sections that were not
-- captured by the producer. New typed recordings may persist the complete dialogue/spell/var
-- evidence needed by manifest v2 and the authoritative action validators.

ALTER TABLE IF EXISTS tick_snapshots
    ADD COLUMN IF NOT EXISTS dialogue JSONB,
    ADD COLUMN IF NOT EXISTS spells JSONB,
    ADD COLUMN IF NOT EXISTS vars JSONB;

DO $$
BEGIN
    IF to_regclass(current_schema() || '.tick_snapshots') IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'ck_tick_snapshots_replay_state_json_sizes'
              AND conrelid = 'tick_snapshots'::regclass
        ) THEN
            ALTER TABLE tick_snapshots ADD CONSTRAINT ck_tick_snapshots_replay_state_json_sizes
                CHECK (
                    dialogue IS NULL OR octet_length(dialogue::text) <= 262144
                    AND (spells IS NULL OR octet_length(spells::text) <= 262144)
                    AND (
                    vars IS NULL OR octet_length(vars::text) <= 262144
                ));
        END IF;
    END IF;
END $$;
