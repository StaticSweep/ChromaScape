-- V34: retain the producer provenance asserted by recording-action/v1.
-- V24 rows predate this field; derive their value from the already closed source enum so the
-- migration does not silently label operator/defaulted evidence as captured.

ALTER TABLE replay_recording_actions
    ADD COLUMN IF NOT EXISTS provenance VARCHAR(32);

UPDATE replay_recording_actions
SET provenance = CASE source
    WHEN 'MICRO6_SEMANTIC_DISPATCH' THEN 'CAPTURED'
    WHEN 'MICRO6_LEGACY_CONVERSION' THEN 'INFERRED'
    WHEN 'OPERATOR_SUPPLIED' THEN 'OPERATOR_SUPPLIED'
    WHEN 'DEFAULTED' THEN 'DEFAULTED'
    ELSE NULL
END
WHERE provenance IS NULL;

ALTER TABLE replay_recording_actions
    ALTER COLUMN provenance SET DEFAULT 'CAPTURED',
    ALTER COLUMN provenance SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'replay_recording_action_provenance_check'
          AND conrelid = 'replay_recording_actions'::regclass
    ) THEN
        ALTER TABLE replay_recording_actions
            ADD CONSTRAINT replay_recording_action_provenance_check
            CHECK (provenance IN ('CAPTURED', 'INFERRED', 'OPERATOR_SUPPLIED', 'DEFAULTED'));
    END IF;
END $$;
