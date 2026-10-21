-- V45: SC-2150 durable analysis scheduling and exact-frame previews. Additive only: V42-V44 are
-- never rewritten, and historical analyses, review revisions and exports keep their stored bytes.
--
-- A preview pins the immutable frame-index content address, the decode ordinal and the source PTS it
-- was extracted from. The unique identity index is what coalesces concurrent equivalent requests
-- into one computation; the job fence (generation + lease) still decides who may publish.

ALTER TABLE mouse_video_jobs
    DROP CONSTRAINT IF EXISTS mouse_video_jobs_kind_check;

ALTER TABLE mouse_video_jobs
    ADD CONSTRAINT mouse_video_jobs_kind_check
        CHECK (kind IN ('IMPORT', 'ANALYSIS', 'PREVIEW'));

ALTER TABLE mouse_video_jobs
    DROP CONSTRAINT IF EXISTS mouse_video_jobs_stage_check;

ALTER TABLE mouse_video_jobs
    ADD CONSTRAINT mouse_video_jobs_stage_check
        CHECK (stage IN ('ACQUIRE', 'PROBE', 'INDEX', 'ANALYSIS', 'PREVIEW', 'PUBLISH', 'DONE'));

ALTER TABLE mouse_video_jobs
    ADD COLUMN IF NOT EXISTS analysis_id UUID REFERENCES mouse_video_analyses(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS preview_id UUID,
    ADD COLUMN IF NOT EXISTS subject_key VARCHAR(160);

CREATE INDEX IF NOT EXISTS idx_mouse_video_jobs_analysis
    ON mouse_video_jobs (analysis_id, kind, status);

CREATE INDEX IF NOT EXISTS idx_mouse_video_jobs_preview
    ON mouse_video_jobs (preview_id, status);

CREATE INDEX IF NOT EXISTS idx_mouse_video_jobs_kind_status
    ON mouse_video_jobs (kind, status, created_at);

CREATE TABLE IF NOT EXISTS mouse_video_frame_previews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner VARCHAR(160) NOT NULL,
    source_id UUID NOT NULL REFERENCES mouse_video_sources(id) ON DELETE CASCADE,
    analysis_id UUID NOT NULL REFERENCES mouse_video_analyses(id) ON DELETE CASCADE,
    request_id VARCHAR(80) NOT NULL,
    request_digest VARCHAR(80) NOT NULL,
    index_checksum VARCHAR(80) NOT NULL,
    frame_ordinal BIGINT NOT NULL CHECK (frame_ordinal >= 0),
    frame_pts VARCHAR(40),
    frame_pts_time VARCHAR(80),
    time_base_numerator VARCHAR(40),
    time_base_denominator VARCHAR(40),
    preview_method VARCHAR(40) NOT NULL,
    max_width INTEGER NOT NULL CHECK (max_width > 0),
    max_height INTEGER NOT NULL CHECK (max_height > 0),
    source_width INTEGER,
    source_height INTEGER,
    output_width INTEGER,
    output_height INTEGER,
    rotation_degrees INTEGER,
    crop_json JSONB,
    transform_json JSONB,
    checksum VARCHAR(80),
    size_bytes BIGINT CHECK (size_bytes IS NULL OR size_bytes >= 0),
    media_type VARCHAR(100),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'READY', 'FAILED', 'CANCELLED')),
    job_id UUID,
    failure_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_mouse_video_frame_previews_owner_request UNIQUE (owner, request_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mouse_video_frame_previews_identity
    ON mouse_video_frame_previews
        (analysis_id, index_checksum, frame_ordinal, preview_method, max_width, max_height);

CREATE INDEX IF NOT EXISTS idx_mouse_video_frame_previews_source
    ON mouse_video_frame_previews (source_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_mouse_video_frame_previews_checksum
    ON mouse_video_frame_previews (checksum)
    WHERE checksum IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mouse_video_frame_previews_state
    ON mouse_video_frame_previews (state, updated_at);
