-- Authoritative coordinate-plane evidence for review sessions. Additive and nullable so
-- historical rows remain unknown rather than being backfilled with a guessed plane.
ALTER TABLE mouse_video_review_sessions
 ADD COLUMN coordinate_plane_identity JSONB,
 ADD COLUMN coordinate_plane_digest VARCHAR(80);
