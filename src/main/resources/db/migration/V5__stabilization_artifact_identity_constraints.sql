-- SC-572 follow-up: durable artifact-id and nonblank identity constraints.
--
-- V1 is already released and must remain byte-for-byte immutable for Flyway checksum
-- validation. This migration carries the additional constraints required by the entity contract
-- for databases that have already applied V1. No legacy identity is invented: deployment fails
-- with an actionable count until an operator archives/removes non-certification rows.

ALTER TABLE stabilization_artifact ADD COLUMN IF NOT EXISTS artifact_id VARCHAR(36);

DO $$
DECLARE legacy_count integer;
DECLARE duplicate_artifact_ids integer;
DECLARE duplicate_run_references integer;
BEGIN
    SELECT count(*) INTO legacy_count FROM stabilization_artifact
        WHERE artifact_id IS NULL OR btrim(artifact_id) = ''
           OR run_id IS NULL OR btrim(run_id) = ''
           OR external_reference IS NULL OR btrim(external_reference) = '';
    IF legacy_count > 0 THEN
        RAISE EXCEPTION
            'stabilization_artifact contains % legacy row(s) without durable nonblank artifact_id, run_id and external_reference; archive or remove non-certification artifacts before applying V5',
            legacy_count;
    END IF;

    SELECT count(*) INTO duplicate_artifact_ids FROM (
        SELECT artifact_id FROM stabilization_artifact
        GROUP BY artifact_id HAVING count(*) > 1
    ) duplicates;
    IF duplicate_artifact_ids > 0 THEN
        RAISE EXCEPTION
            'stabilization_artifact contains % duplicate artifact_id value(s); resolve them before applying V5',
            duplicate_artifact_ids;
    END IF;

    SELECT count(*) INTO duplicate_run_references FROM (
        SELECT run_id, external_reference FROM stabilization_artifact
        GROUP BY run_id, external_reference HAVING count(*) > 1
    ) duplicates;
    IF duplicate_run_references > 0 THEN
        RAISE EXCEPTION
            'stabilization_artifact contains % duplicate (run_id, external_reference) mapping(s); resolve them before applying V5',
            duplicate_run_references;
    END IF;
END $$;

ALTER TABLE stabilization_artifact ALTER COLUMN artifact_id SET NOT NULL;
ALTER TABLE stabilization_artifact ALTER COLUMN run_id SET NOT NULL;
ALTER TABLE stabilization_artifact ALTER COLUMN external_reference SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_constraint
        WHERE conname = 'stabilization_artifact_artifact_id_nonblank'
          AND conrelid = 'stabilization_artifact'::regclass) THEN
        ALTER TABLE stabilization_artifact
            ADD CONSTRAINT stabilization_artifact_artifact_id_nonblank
            CHECK (btrim(artifact_id) <> '');
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_constraint
        WHERE conname = 'stabilization_artifact_run_id_nonblank'
          AND conrelid = 'stabilization_artifact'::regclass) THEN
        ALTER TABLE stabilization_artifact
            ADD CONSTRAINT stabilization_artifact_run_id_nonblank
            CHECK (btrim(run_id) <> '');
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_constraint
        WHERE conname = 'stabilization_artifact_external_reference_nonblank'
          AND conrelid = 'stabilization_artifact'::regclass) THEN
        ALTER TABLE stabilization_artifact
            ADD CONSTRAINT stabilization_artifact_external_reference_nonblank
            CHECK (btrim(external_reference) <> '');
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS stabilization_artifact_artifact_id_key
    ON stabilization_artifact (artifact_id);

CREATE UNIQUE INDEX IF NOT EXISTS stabilization_artifact_run_ref_key
    ON stabilization_artifact (run_id, external_reference);
