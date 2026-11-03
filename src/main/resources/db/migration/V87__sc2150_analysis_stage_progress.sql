-- V87: SC-2150 reports bounded sample extraction and the in-JVM tracking passes as their own job
-- stages, so long assisted analyses are not stuck at ANALYSIS. V83 is never rewritten; the stage
-- vocabulary is widened here and historical rows keep their stored stage.
ALTER TABLE mouse_video_jobs
    DROP CONSTRAINT IF EXISTS mouse_video_jobs_stage_check;

ALTER TABLE mouse_video_jobs
    ADD CONSTRAINT mouse_video_jobs_stage_check
        CHECK (stage IN ('ACQUIRE', 'PROBE', 'INDEX', 'ANALYSIS', 'ANALYSIS_SAMPLES',
                         'ANALYSIS_TRACKING', 'PREVIEW', 'PUBLISH', 'DONE'));
