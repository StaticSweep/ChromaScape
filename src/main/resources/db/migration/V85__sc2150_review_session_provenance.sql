-- Additive protocol evidence. Historical revisions/exports remain unchanged and unqualified.
CREATE TABLE mouse_video_review_sessions (
 id UUID PRIMARY KEY, source_id UUID NOT NULL REFERENCES mouse_video_sources(id),
 analysis_id UUID NOT NULL REFERENCES mouse_video_analyses(id), owner VARCHAR(160) NOT NULL,
 actor VARCHAR(160) NOT NULL, request_id VARCHAR(80) NOT NULL, request_digest VARCHAR(80) NOT NULL,
 original_digest VARCHAR(80) NOT NULL, frame_index_checksum VARCHAR(80) NOT NULL,
 origin_group_id VARCHAR(240) NOT NULL, policy_digest VARCHAR(80) NOT NULL,
 split VARCHAR(16) NOT NULL CHECK (split IN ('dev','heldout','unassigned')),
 mode VARCHAR(32) NOT NULL CHECK (mode IN ('WITHOUT_SUGGESTIONS','DETECTOR_ASSISTED')),
 state VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','SEALED','ABANDONED','INVALIDATED')),
 start_ordinal BIGINT NOT NULL CHECK (start_ordinal >= 0), end_ordinal BIGINT NOT NULL,
 frame_count BIGINT NOT NULL, protocol_version VARCHAR(40) NOT NULL DEFAULT 'review-provenance/v1',
 annotation_attestation BOOLEAN NOT NULL, latest_revision_id UUID REFERENCES mouse_video_review_revisions(id),
 seal_request_id VARCHAR(80), seal_request_digest VARCHAR(80), sealed_export TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), sealed_at TIMESTAMPTZ,
 UNIQUE(owner, actor, request_id), CHECK (end_ordinal >= start_ordinal),
 CHECK (end_ordinal < frame_count), CHECK (end_ordinal - start_ordinal < 2000)
);
CREATE INDEX idx_mouse_video_session_group ON mouse_video_review_sessions(owner,actor,origin_group_id,state);
CREATE TABLE mouse_video_corpus_memberships (
 owner VARCHAR(160) NOT NULL, source_id UUID NOT NULL REFERENCES mouse_video_sources(id),
 original_digest VARCHAR(80) NOT NULL, manifest_digest VARCHAR(80) NOT NULL,
 origin_group_id VARCHAR(240) NOT NULL, split VARCHAR(16) NOT NULL CHECK(split IN ('dev','heldout')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(owner,source_id,manifest_digest)
);
CREATE TABLE mouse_video_review_exposures (
 id UUID PRIMARY KEY, owner VARCHAR(160) NOT NULL, actor VARCHAR(160) NOT NULL,
 origin_group_id VARCHAR(240) NOT NULL, original_digest VARCHAR(80) NOT NULL,
 source_id UUID NOT NULL REFERENCES mouse_video_sources(id), exposure_kind VARCHAR(40) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(owner,actor,origin_group_id,source_id,exposure_kind)
);
CREATE INDEX idx_mouse_video_exposure_actor_group ON mouse_video_review_exposures(owner,actor,origin_group_id);
ALTER TABLE mouse_video_review_revisions
 ADD COLUMN review_session_id UUID REFERENCES mouse_video_review_sessions(id),
 ADD COLUMN provenance_json JSONB,
 ADD COLUMN client_edit_version BIGINT,
 ADD COLUMN snapshot_digest VARCHAR(80),
 ADD COLUMN applied_source_revision BIGINT;
CREATE INDEX idx_mouse_video_revision_session ON mouse_video_review_revisions(review_session_id,created_at);
