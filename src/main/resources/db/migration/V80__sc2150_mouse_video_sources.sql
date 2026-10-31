-- V80: SC-2150 external video import and human-behavior analysis.
-- Additive, owner-scoped metadata for uploaded and supported YouTube sources. Media bytes stay on
-- the content-addressed filesystem byte store; these tables hold durable provenance, fenced job
-- state, verified artifact registration and append-only review history. External media never uses
-- native DebugSession/tick identifiers, and the native_telemetry / exact_ticks_available /
-- control_allowed / training_eligible flags are constrained false in SQL so no row can claim more
-- than the evidence supports.

CREATE TABLE IF NOT EXISTS mouse_video_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner VARCHAR(160) NOT NULL,
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('UPLOAD', 'YOUTUBE')),
    provider_id VARCHAR(160),
    provider_url VARCHAR(2048),
    declared_origin VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (declared_origin IN ('HUMAN', 'GENERATED', 'UNKNOWN')),
    origin_review VARCHAR(20) NOT NULL DEFAULT 'UNREVIEWED'
        CHECK (origin_review IN ('UNREVIEWED', 'REVIEWED', 'REJECTED')),
    task_key VARCHAR(120),
    skill VARCHAR(120),
    context_label VARCHAR(120),
    place_label VARCHAR(120),
    original_checksum VARCHAR(80),
    original_size_bytes BIGINT CHECK (original_size_bytes IS NULL OR original_size_bytes >= 0),
    original_media_type VARCHAR(100),
    lifecycle VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (lifecycle IN ('OPEN', 'DELETED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    review_revision BIGINT NOT NULL DEFAULT 0 CHECK (review_revision >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mouse_video_sources_owner_created
    ON mouse_video_sources (owner, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_mouse_video_sources_owner_kind
    ON mouse_video_sources (owner, source_kind);

CREATE TABLE IF NOT EXISTS mouse_video_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id UUID NOT NULL REFERENCES mouse_video_sources(id) ON DELETE CASCADE,
    owner VARCHAR(160) NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('IMPORT', 'ANALYSIS')),
    stage VARCHAR(24) NOT NULL DEFAULT 'ACQUIRE'
        CHECK (stage IN ('ACQUIRE', 'PROBE', 'INDEX', 'ANALYSIS', 'PUBLISH', 'DONE')),
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT',
                          'CANCELLING', 'CANCELLED', 'NEEDS_RECONCILIATION')),
    request_id VARCHAR(80) NOT NULL,
    request_digest VARCHAR(80) NOT NULL,
    config_digest VARCHAR(80) NOT NULL DEFAULT '',
    toolchain_digest VARCHAR(80) NOT NULL DEFAULT '',
    generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt > 0),
    progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    cancellation_revision BIGINT NOT NULL DEFAULT 0 CHECK (cancellation_revision >= 0),
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_mouse_video_jobs_owner_request UNIQUE (owner, request_id)
);

CREATE INDEX IF NOT EXISTS idx_mouse_video_jobs_source
    ON mouse_video_jobs (source_id);

CREATE INDEX IF NOT EXISTS idx_mouse_video_jobs_status
    ON mouse_video_jobs (status, created_at);

CREATE TABLE IF NOT EXISTS mouse_video_job_events (
    id BIGSERIAL PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES mouse_video_jobs(id) ON DELETE CASCADE,
    generation BIGINT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    metadata_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mouse_video_job_events_job
    ON mouse_video_job_events (job_id, id);

CREATE TABLE IF NOT EXISTS mouse_video_analyses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id UUID NOT NULL REFERENCES mouse_video_sources(id) ON DELETE CASCADE,
    owner VARCHAR(160) NOT NULL,
    original_digest VARCHAR(80) NOT NULL,
    algorithm_id VARCHAR(80) NOT NULL,
    template_pack_id VARCHAR(80),
    config_digest VARCHAR(80) NOT NULL,
    toolchain_digest VARCHAR(80) NOT NULL,
    coverage_summary VARCHAR(1024),
    manifest_checksum VARCHAR(80),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'COMPLETE', 'FAILED')),
    native_telemetry BOOLEAN NOT NULL DEFAULT false CHECK (native_telemetry = false),
    exact_ticks_available BOOLEAN NOT NULL DEFAULT false CHECK (exact_ticks_available = false),
    control_allowed BOOLEAN NOT NULL DEFAULT false CHECK (control_allowed = false),
    training_eligible BOOLEAN NOT NULL DEFAULT false CHECK (training_eligible = false),
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_mouse_video_analyses_dedup
        UNIQUE (source_id, original_digest, config_digest, toolchain_digest)
);

CREATE INDEX IF NOT EXISTS idx_mouse_video_analyses_source
    ON mouse_video_analyses (source_id, created_at DESC);

CREATE TABLE IF NOT EXISTS mouse_video_artifact_refs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner VARCHAR(160) NOT NULL,
    source_id UUID REFERENCES mouse_video_sources(id) ON DELETE CASCADE,
    analysis_id UUID REFERENCES mouse_video_analyses(id) ON DELETE CASCADE,
    role VARCHAR(24) NOT NULL
        CHECK (role IN ('ORIGINAL', 'PROXY', 'THUMBNAIL', 'FRAME_INDEX', 'OBSERVATIONS',
                        'QUALITY', 'REVIEW_EXPORT', 'MANIFEST')),
    checksum VARCHAR(80) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    media_type VARCHAR(100),
    state VARCHAR(16) NOT NULL DEFAULT 'STAGING'
        CHECK (state IN ('STAGING', 'FINALIZED', 'QUARANTINED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mouse_video_artifact_source_role
    ON mouse_video_artifact_refs (source_id, role) WHERE analysis_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mouse_video_artifact_analysis_role
    ON mouse_video_artifact_refs (analysis_id, role) WHERE analysis_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS mouse_video_review_revisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id UUID NOT NULL REFERENCES mouse_video_sources(id) ON DELETE CASCADE,
    analysis_id UUID REFERENCES mouse_video_analyses(id) ON DELETE SET NULL,
    predecessor_id UUID REFERENCES mouse_video_review_revisions(id),
    actor VARCHAR(160) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    expected_base_revision BIGINT NOT NULL DEFAULT 0 CHECK (expected_base_revision >= 0),
    source_use_review VARCHAR(24) NOT NULL DEFAULT 'INSPECTION_ONLY'
        CHECK (source_use_review IN ('INSPECTION_ONLY', 'TRAINING_REQUESTED', 'TRAINING_DENIED')),
    corrections_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    manifest_checksum VARCHAR(80),
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (state IN ('DRAFT', 'REVIEWED', 'SUPERSEDED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mouse_video_review_revisions_source
    ON mouse_video_review_revisions (source_id, created_at DESC);
