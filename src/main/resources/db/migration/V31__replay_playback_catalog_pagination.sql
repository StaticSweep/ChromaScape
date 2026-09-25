-- V31: indexes for the read-only observed playback catalog and frame/log pages.
--
-- debug_sessions is Flyway-owned since V24, so its playback indexes apply unconditionally.
-- tick_snapshots/tick_logs remain Hibernate-managed; those keep existence guards and the
-- post-Hibernate playback index convergence runner applies the same definitions after creation.
-- Existing evidence is never rewritten or removed.

CREATE INDEX IF NOT EXISTS idx_debug_sessions_playback_keyset
    ON debug_sessions (start_time DESC, id DESC)
    WHERE recording_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_debug_sessions_playback_account_keyset
    ON debug_sessions (account_name, start_time DESC, id DESC)
    WHERE recording_id IS NOT NULL;

DO $$
BEGIN
    IF to_regclass('tick_snapshots') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_tick_snapshots_playback_session_tick_id
            ON tick_snapshots (session_id, tick, id);
    END IF;

    IF to_regclass('tick_logs') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_tick_logs_playback_session_tick_id
            ON tick_logs (session_id, tick, id);
    END IF;
END $$;
