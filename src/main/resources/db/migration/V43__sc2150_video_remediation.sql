-- V43: SC-2150 review-remediation schema. Additive only; V42 is never rewritten. Adds trusted
-- original qualification, intake reservations and owner budgets, fenced job row versioning, and
-- immutable versioned export snapshots, and narrows the artifact uniqueness rules so ORIGINAL and
-- other singleton roles stay unique while EXPORT snapshots may accumulate.

ALTER TABLE mouse_video_sources
    ADD COLUMN IF NOT EXISTS verified_media_type VARCHAR(100),
    ADD COLUMN IF NOT EXISTS original_qualified BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE mouse_video_jobs
    ADD COLUMN IF NOT EXISTS cleanup_reason VARCHAR(512),
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE mouse_video_review_revisions
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(80);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mouse_video_review_source_request
    ON mouse_video_review_revisions (source_id, request_id)
    WHERE request_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS mouse_video_owner_budgets (
    owner VARCHAR(160) PRIMARY KEY,
    charged_bytes BIGINT NOT NULL DEFAULT 0 CHECK (charged_bytes >= 0),
    reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mouse_video_intake_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner VARCHAR(160) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    request_digest VARCHAR(80),
    declared_bytes BIGINT NOT NULL CHECK (declared_bytes >= 0),
    actual_bytes BIGINT CHECK (actual_bytes IS NULL OR actual_bytes >= 0),
    media_checksum VARCHAR(80),
    state VARCHAR(16) NOT NULL DEFAULT 'HELD'
        CHECK (state IN ('HELD', 'COMMITTED', 'RELEASED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_mouse_video_reservations_owner_request UNIQUE (owner, request_id)
);

CREATE INDEX IF NOT EXISTS idx_mouse_video_reservations_state_expiry
    ON mouse_video_intake_reservations (state, expires_at);

CREATE TABLE IF NOT EXISTS mouse_video_exports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner VARCHAR(160) NOT NULL,
    source_id UUID NOT NULL REFERENCES mouse_video_sources(id) ON DELETE CASCADE,
    request_id VARCHAR(80) NOT NULL,
    request_digest VARCHAR(80) NOT NULL,
    contract VARCHAR(80) NOT NULL,
    analysis_id UUID REFERENCES mouse_video_analyses(id) ON DELETE SET NULL,
    review_revision BIGINT NOT NULL DEFAULT 0 CHECK (review_revision >= 0),
    manifest_checksum VARCHAR(80) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_mouse_video_exports_owner_request UNIQUE (owner, request_id),
    CONSTRAINT uq_mouse_video_exports_source_digest UNIQUE (source_id, request_digest)
);

CREATE INDEX IF NOT EXISTS idx_mouse_video_exports_owner_created
    ON mouse_video_exports (owner, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_mouse_video_exports_source
    ON mouse_video_exports (source_id, created_at DESC);

ALTER TABLE mouse_video_artifact_refs
    ADD COLUMN IF NOT EXISTS export_id UUID;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'mouse_video_artifact_refs_role_check'
    ) THEN
        ALTER TABLE mouse_video_artifact_refs
            DROP CONSTRAINT mouse_video_artifact_refs_role_check;
    END IF;
    ALTER TABLE mouse_video_artifact_refs
        ADD CONSTRAINT mouse_video_artifact_refs_role_check
        CHECK (role IN ('ORIGINAL', 'PROXY', 'THUMBNAIL', 'FRAME_INDEX', 'OBSERVATIONS',
                        'QUALITY', 'REVIEW_EXPORT', 'MANIFEST', 'EXPORT'));
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'mouse_video_artifact_refs_export_fk'
    ) THEN
        ALTER TABLE mouse_video_artifact_refs
            ADD CONSTRAINT mouse_video_artifact_refs_export_fk
            FOREIGN KEY (export_id) REFERENCES mouse_video_exports(id) ON DELETE CASCADE;
    END IF;
END $$;

-- Replace the source/role uniqueness narrowly: EXPORT artifacts may accumulate per snapshot.
DROP INDEX IF EXISTS uq_mouse_video_artifact_source_role;
CREATE UNIQUE INDEX IF NOT EXISTS uq_mouse_video_artifact_source_role
    ON mouse_video_artifact_refs (source_id, role)
    WHERE analysis_id IS NULL AND role <> 'EXPORT';

CREATE UNIQUE INDEX IF NOT EXISTS uq_mouse_video_artifact_export_role
    ON mouse_video_artifact_refs (export_id, role)
    WHERE export_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mouse_video_jobs_status_lease
    ON mouse_video_jobs (status, lease_expires_at);
