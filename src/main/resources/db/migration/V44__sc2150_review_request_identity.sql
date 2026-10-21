-- V44: SC-2150 review request identity and correction contract version. Additive only; V42 and
-- V43 are never rewritten and historical revisions keep their stored content untouched.
--
-- request_digest is the canonical digest of the validated correction payload plus its binding
-- fields, used to distinguish a transport retry from a changed body that reuses the same request
-- identifier. Historical rows stay NULL on purpose: no digest is fabricated for a row that was
-- written before this contract existed, and a retry against such a row is compared through a
-- reconstructible semantic projection instead.

ALTER TABLE mouse_video_review_revisions
    ADD COLUMN IF NOT EXISTS contract_version VARCHAR(40) NOT NULL DEFAULT 'corrections/v0',
    ADD COLUMN IF NOT EXISTS request_digest VARCHAR(80);

CREATE INDEX IF NOT EXISTS idx_mouse_video_review_revisions_predecessor
    ON mouse_video_review_revisions (predecessor_id);
