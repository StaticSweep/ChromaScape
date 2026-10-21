-- V46: SC-2150 scheduling identity hardening. Additive only.
--
-- Analyses pin the frozen cursor-template content address so queued work executes the exact
-- template bytes its identity was scheduled with, not whatever a mutable configured path holds
-- later. Scheduling requests persist every (owner, requestId) identity, including coalesced
-- aliases that created no new job/preview row, so a changed body under a reused request
-- identifier is durably a conflict.

ALTER TABLE mouse_video_analyses
    ADD COLUMN IF NOT EXISTS template_checksum VARCHAR(80);

CREATE TABLE IF NOT EXISTS mouse_video_scheduling_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner VARCHAR(160) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    request_digest VARCHAR(80) NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('ANALYSIS', 'PREVIEW')),
    source_id UUID NOT NULL REFERENCES mouse_video_sources(id) ON DELETE CASCADE,
    analysis_id UUID,
    preview_id UUID,
    job_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_mouse_video_scheduling_requests_owner_request UNIQUE (owner, request_id)
);

CREATE INDEX IF NOT EXISTS idx_mouse_video_scheduling_requests_source
    ON mouse_video_scheduling_requests (source_id, created_at DESC);
