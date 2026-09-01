-- V31: indexes for the read-only observed playback catalog and frame/log pages.
--
-- The debugger tables are historically managed by Hibernate ddl-auto and may not exist yet when
-- Flyway runs on a brand-new database. The guards keep V31 safe in that bootstrap case; the
-- post-Hibernate playback index convergence runner applies the same definitions after creation.
-- Existing evidence is never rewritten or removed.

DO $$
BEGIN
    IF to_regclass(current_schema() || '.debug_sessions') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_debug_sessions_playback_keyset
            ON debug_sessions (start_time DESC, id DESC)
            WHERE recording_id IS NOT NULL;

        CREATE INDEX IF NOT EXISTS idx_debug_sessions_playback_account_keyset
            ON debug_sessions (account_name, start_time DESC, id DESC)
            WHERE recording_id IS NOT NULL;
    END IF;

    IF to_regclass(current_schema() || '.tick_snapshots') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_tick_snapshots_playback_session_tick_id
            ON tick_snapshots (session_id, tick, id);
    END IF;

    IF to_regclass(current_schema() || '.tick_logs') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_tick_logs_playback_session_tick_id
            ON tick_logs (session_id, tick, id);
    END IF;
END $$;
