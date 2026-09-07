-- Repair replay/debug foreign keys that older migrations guarded by constraint name alone.
-- PostgreSQL constraint names are not database-global, so a shared database containing more
-- than one application schema could cause a later schema to skip its own foreign key.

DO $$
DECLARE
    target_schema TEXT := '${flyway:defaultSchema}';
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = to_regclass(format('%I.%I', target_schema, 'replay_recording_actions'))
          AND conname = 'fk_replay_recording_actions_session'
    ) THEN
        EXECUTE format(
            'ALTER TABLE %I.replay_recording_actions '
            'ADD CONSTRAINT fk_replay_recording_actions_session '
            'FOREIGN KEY (session_id) REFERENCES %I.debug_sessions(session_id) ON DELETE RESTRICT',
            target_schema,
            target_schema
        );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = to_regclass(format('%I.%I', target_schema, 'replay_recording_actions'))
          AND conname = 'fk_replay_recording_actions_recording'
    ) THEN
        EXECUTE format(
            'ALTER TABLE %I.replay_recording_actions '
            'ADD CONSTRAINT fk_replay_recording_actions_recording '
            'FOREIGN KEY (recording_id) REFERENCES %I.recording_sessions(recording_id) ON DELETE RESTRICT',
            target_schema,
            target_schema
        );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = to_regclass(format('%I.%I', target_schema, 'replay_tick_batch_receipts'))
          AND conname = 'fk_replay_tick_batch_receipt_session'
    ) THEN
        EXECUTE format(
            'ALTER TABLE %I.replay_tick_batch_receipts '
            'ADD CONSTRAINT fk_replay_tick_batch_receipt_session '
            'FOREIGN KEY (session_id) REFERENCES %I.debug_sessions(session_id) ON DELETE RESTRICT',
            target_schema,
            target_schema
        );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = to_regclass(format('%I.%I', target_schema, 'packet_observations'))
          AND conname = 'fk_packet_observations_session'
    ) THEN
        EXECUTE format(
            'ALTER TABLE %I.packet_observations '
            'ADD CONSTRAINT fk_packet_observations_session '
            'FOREIGN KEY (session_id) REFERENCES %I.debug_sessions(session_id) ON DELETE RESTRICT',
            target_schema,
            target_schema
        );
    END IF;
END $$;
