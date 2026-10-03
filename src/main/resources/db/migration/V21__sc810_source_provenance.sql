-- SC-810: immutable source-manifest references and exact SC-609 run bindings.
CREATE TABLE IF NOT EXISTS source_manifest_references (
    id UUID PRIMARY KEY,
    version BIGINT,
    task_key VARCHAR(160) NOT NULL,
    schema_version VARCHAR(80) NOT NULL,
    manifest_hash VARCHAR(71) NOT NULL,
    snapshot_id VARCHAR(40) NOT NULL,
    repository_id VARCHAR(200) NOT NULL,
    commit_id VARCHAR(64) NOT NULL,
    tree_id VARCHAR(64) NOT NULL,
    build_id VARCHAR(200) NOT NULL,
    artifact_digest VARCHAR(71) NOT NULL,
    verification_state VARCHAR(40) NOT NULL,
    source_provenance VARCHAR(40) NOT NULL,
    retention_class VARCHAR(40) NOT NULL,
    confidentiality VARCHAR(40) NOT NULL,
    environment VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    pruned_at TIMESTAMP,
    redacted_at TIMESTAMP,
    manifest_json TEXT NOT NULL
);
ALTER TABLE source_manifest_references
    ADD CONSTRAINT ck_source_manifest_references_verification_state
        CHECK (verification_state = 'VERIFIED'),
    ADD CONSTRAINT ck_source_manifest_references_source_provenance
        CHECK (source_provenance = 'VERIFIED');
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_manifest_references_manifest_hash
    ON source_manifest_references (manifest_hash);
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_manifest_references_snapshot_id
    ON source_manifest_references (snapshot_id);

CREATE TABLE IF NOT EXISTS source_run_bindings (
    id UUID PRIMARY KEY,
    run_id VARCHAR(160) NOT NULL,
    recording_id VARCHAR(160),
    task_key VARCHAR(160) NOT NULL,
    manifest_hash VARCHAR(71) NOT NULL,
    snapshot_id VARCHAR(40) NOT NULL,
    repository_id VARCHAR(200) NOT NULL,
    commit_id VARCHAR(64) NOT NULL,
    tree_id VARCHAR(64) NOT NULL,
    build_id VARCHAR(200) NOT NULL,
    artifact_digest VARCHAR(71) NOT NULL,
    environment VARCHAR(120) NOT NULL,
    verification_state VARCHAR(40) NOT NULL,
    source_provenance VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
ALTER TABLE source_run_bindings
    ADD CONSTRAINT ck_source_run_bindings_verification_state
        CHECK (verification_state = 'VERIFIED'),
    ADD CONSTRAINT ck_source_run_bindings_source_provenance
        CHECK (source_provenance = 'VERIFIED');
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_run_bindings_run_id ON source_run_bindings (run_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_run_bindings_recording_id
    ON source_run_bindings (recording_id) WHERE recording_id IS NOT NULL;

-- Content-addressed bytes are immutable.  Retention markers are the only mutable fields, and
-- they may move only from NULL to a timestamp.  A content object can be shared by several
-- manifests, so access is also gated by the manifest reference's retention state.
CREATE TABLE IF NOT EXISTS source_content_objects (
    id UUID PRIMARY KEY,
    content_hash VARCHAR(71) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_bytes BYTEA NOT NULL,
    created_at TIMESTAMP NOT NULL,
    pruned_at TIMESTAMP,
    redacted_at TIMESTAMP,
    CONSTRAINT source_content_hash_format CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT source_content_size_nonnegative CHECK (size_bytes >= 0),
    CONSTRAINT source_content_size_matches CHECK (size_bytes = octet_length(content_bytes))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_source_content_objects_content_hash
    ON source_content_objects (content_hash);

-- Bindings refer to an already persisted immutable manifest.  This prevents a run row from
-- pointing at a source identity that was never verified by ChromaScape.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_source_run_bindings_manifest_hash'
          AND conrelid = 'source_run_bindings'::regclass) THEN
        ALTER TABLE source_run_bindings
            ADD CONSTRAINT fk_source_run_bindings_manifest_hash
            FOREIGN KEY (manifest_hash)
            REFERENCES source_manifest_references (manifest_hash);
    END IF;
END $$;

CREATE OR REPLACE FUNCTION sc810_immutable_manifest_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.task_key IS DISTINCT FROM OLD.task_key
       OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
       OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
       OR NEW.snapshot_id IS DISTINCT FROM OLD.snapshot_id
       OR NEW.repository_id IS DISTINCT FROM OLD.repository_id
       OR NEW.commit_id IS DISTINCT FROM OLD.commit_id
       OR NEW.tree_id IS DISTINCT FROM OLD.tree_id
       OR NEW.build_id IS DISTINCT FROM OLD.build_id
       OR NEW.artifact_digest IS DISTINCT FROM OLD.artifact_digest
       OR NEW.verification_state IS DISTINCT FROM OLD.verification_state
       OR NEW.source_provenance IS DISTINCT FROM OLD.source_provenance
       OR NEW.retention_class IS DISTINCT FROM OLD.retention_class
       OR NEW.confidentiality IS DISTINCT FROM OLD.confidentiality
       OR NEW.environment IS DISTINCT FROM OLD.environment
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.manifest_json IS DISTINCT FROM OLD.manifest_json
       OR (OLD.pruned_at IS NOT NULL AND NEW.pruned_at IS DISTINCT FROM OLD.pruned_at)
       OR (OLD.redacted_at IS NOT NULL AND NEW.redacted_at IS DISTINCT FROM OLD.redacted_at)
       OR (NEW.pruned_at IS NOT NULL AND NEW.pruned_at < OLD.pruned_at)
       OR (NEW.redacted_at IS NOT NULL AND NEW.redacted_at < OLD.redacted_at) THEN
        RAISE EXCEPTION 'SC-810 source manifest identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sc810_immutable_manifest_reference
    ON source_manifest_references;
CREATE TRIGGER trg_sc810_immutable_manifest_reference
BEFORE UPDATE ON source_manifest_references
FOR EACH ROW EXECUTE FUNCTION sc810_immutable_manifest_reference();

CREATE OR REPLACE FUNCTION sc810_immutable_content_object()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
       OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
       OR NEW.content_bytes IS DISTINCT FROM OLD.content_bytes
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR (OLD.pruned_at IS NOT NULL AND NEW.pruned_at IS DISTINCT FROM OLD.pruned_at)
       OR (OLD.redacted_at IS NOT NULL AND NEW.redacted_at IS DISTINCT FROM OLD.redacted_at)
       OR (NEW.pruned_at IS NOT NULL AND NEW.pruned_at < OLD.pruned_at)
       OR (NEW.redacted_at IS NOT NULL AND NEW.redacted_at < OLD.redacted_at) THEN
        RAISE EXCEPTION 'SC-810 source content identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sc810_immutable_content_object
    ON source_content_objects;
CREATE TRIGGER trg_sc810_immutable_content_object
BEFORE UPDATE ON source_content_objects
FOR EACH ROW EXECUTE FUNCTION sc810_immutable_content_object();

-- A recording is assigned after a run is created.  That one lifecycle transition is allowed, but
-- neither the run identity nor its source identity may ever be replaced or cleared.
CREATE OR REPLACE FUNCTION sc810_immutable_run_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.task_key IS DISTINCT FROM OLD.task_key
       OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
       OR NEW.snapshot_id IS DISTINCT FROM OLD.snapshot_id
       OR NEW.repository_id IS DISTINCT FROM OLD.repository_id
       OR NEW.commit_id IS DISTINCT FROM OLD.commit_id
       OR NEW.tree_id IS DISTINCT FROM OLD.tree_id
       OR NEW.build_id IS DISTINCT FROM OLD.build_id
       OR NEW.artifact_digest IS DISTINCT FROM OLD.artifact_digest
       OR NEW.environment IS DISTINCT FROM OLD.environment
       OR NEW.verification_state IS DISTINCT FROM OLD.verification_state
       OR NEW.source_provenance IS DISTINCT FROM OLD.source_provenance
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR (OLD.recording_id IS NOT NULL AND NEW.recording_id IS DISTINCT FROM OLD.recording_id) THEN
        RAISE EXCEPTION 'SC-810 source run identity is immutable';
    END IF;
    IF OLD.recording_id IS NULL AND NEW.recording_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF OLD.recording_id IS NULL AND (NEW.recording_id IS NULL OR btrim(NEW.recording_id) = '') THEN
        RAISE EXCEPTION 'SC-810 recording binding must be a nonblank one-way assignment';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sc810_immutable_run_binding
    ON source_run_bindings;
CREATE TRIGGER trg_sc810_immutable_run_binding
BEFORE UPDATE ON source_run_bindings
FOR EACH ROW EXECUTE FUNCTION sc810_immutable_run_binding();

-- Source-read audit records intentionally contain identities and outcomes only; source bytes,
-- authorization credentials, host paths, and upstream URLs never enter this table.
CREATE TABLE IF NOT EXISTS source_read_audit_events (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(160) NOT NULL,
    operation VARCHAR(40) NOT NULL,
    run_id VARCHAR(160),
    recording_id VARCHAR(160),
    task_key VARCHAR(160),
    snapshot_id VARCHAR(40),
    file_id VARCHAR(80),
    content_hash VARCHAR(71),
    result VARCHAR(80) NOT NULL,
    environment VARCHAR(120),
    correlation_id VARCHAR(160),
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_source_read_audit_run_created
    ON source_read_audit_events (run_id, created_at);
CREATE INDEX IF NOT EXISTS idx_source_read_audit_snapshot_created
    ON source_read_audit_events (snapshot_id, created_at);
ALTER TABLE source_read_audit_events ADD COLUMN IF NOT EXISTS content_hash VARCHAR(71);
