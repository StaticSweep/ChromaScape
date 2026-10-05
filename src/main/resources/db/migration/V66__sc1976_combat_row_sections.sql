-- V40: additive SC-1976 combat row sections.
-- Old rows keep NULL for sections that were not captured by the producer. Schema-4 micro6
-- batches may persist inputs (cursor/button/menu/clicked), walkable-tile flags, hazards, and
-- the encounter phase envelope needed by the combat tick row and SC-752 coverage.

ALTER TABLE IF EXISTS tick_snapshots
    ADD COLUMN IF NOT EXISTS inputs JSONB,
    ADD COLUMN IF NOT EXISTS tiles JSONB,
    ADD COLUMN IF NOT EXISTS hazards JSONB,
    ADD COLUMN IF NOT EXISTS phase JSONB;

DO $$
BEGIN
    IF to_regclass(current_schema() || '.tick_snapshots') IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conname = 'ck_tick_snapshots_combat_row_json_sizes'
              AND conrelid = 'tick_snapshots'::regclass
        ) THEN
            ALTER TABLE tick_snapshots ADD CONSTRAINT ck_tick_snapshots_combat_row_json_sizes
                CHECK (
                    (inputs IS NULL OR octet_length(inputs::text) <= 262144)
                    AND (tiles IS NULL OR octet_length(tiles::text) <= 262144)
                    AND (hazards IS NULL OR octet_length(hazards::text) <= 262144)
                    AND (phase IS NULL OR octet_length(phase::text) <= 262144)
                );
        END IF;
    END IF;
END $$;
