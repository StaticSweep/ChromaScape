-- SC-572: durable, unambiguous external-reference mapping for verified stabilization artifacts.
--
-- Owned by Flyway; the NOT NULL and uniqueness constraints below must never be applied via
-- Hibernate ddl-auto update. The migration is idempotent: on a fresh database the table is created
-- (Flyway runs before Hibernate schema generation), and on an existing database the columns are
-- added if absent and the constraints applied in place.
--
-- Legacy preflight (explicit procedure): rows without a durable run_id/external_reference cannot
-- receive a real evidence reference (a fake one is never invented). Before enforcing NOT NULL the
-- migration fails deployment clearly with the legacy row count; the operator must archive or
-- remove non-certification legacy artifacts first.

CREATE TABLE IF NOT EXISTS stabilization_artifact (
    id BIGSERIAL PRIMARY KEY,
    artifact_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(100),
    attempt_id VARCHAR(100),
    external_reference VARCHAR(512),
    evidence_type VARCHAR(50),
    sha256 VARCHAR(200),
    size_bytes BIGINT,
    media_type VARCHAR(100),
    created_at TIMESTAMP NOT NULL
);

ALTER TABLE stabilization_artifact ADD COLUMN IF NOT EXISTS external_reference VARCHAR(512);
ALTER TABLE stabilization_artifact ADD COLUMN IF NOT EXISTS run_id VARCHAR(100);

DO $$
DECLARE legacy_count integer;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_class
               WHERE relname = 'stabilization_artifact' AND relkind = 'r') THEN
        SELECT count(*) INTO legacy_count FROM stabilization_artifact
            WHERE run_id IS NULL OR run_id = ''
               OR external_reference IS NULL OR external_reference = '';
        IF legacy_count > 0 THEN
            RAISE EXCEPTION
                'stabilization_artifact contains % legacy row(s) without a durable (run_id, external_reference); archive or remove non-certification legacy artifacts before applying the SC-572 external-reference constraints',
                legacy_count;
        END IF;
    END IF;
END $$;

ALTER TABLE stabilization_artifact ALTER COLUMN run_id SET NOT NULL;
ALTER TABLE stabilization_artifact ALTER COLUMN external_reference SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS stabilization_artifact_run_ref_key
    ON stabilization_artifact (run_id, external_reference);
