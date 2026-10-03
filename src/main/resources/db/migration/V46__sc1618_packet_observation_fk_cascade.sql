-- SC-1618: packet observations are owned by their debug session.
-- V45 could create this foreign key with RESTRICT when the older constraint was absent, while
-- V39 created it with CASCADE when the constraint already existed. Normalize both paths forward.

DO $$
DECLARE
    target_schema TEXT := '${flyway:defaultSchema}';
BEGIN
    IF to_regclass(format('%I.%I', target_schema, 'packet_observations')) IS NOT NULL
       AND to_regclass(format('%I.%I', target_schema, 'debug_sessions')) IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE %I.packet_observations DROP CONSTRAINT IF EXISTS fk_packet_observations_session',
            target_schema
        );
        EXECUTE format(
            'ALTER TABLE %I.packet_observations '
            'ADD CONSTRAINT fk_packet_observations_session '
            'FOREIGN KEY (session_id) REFERENCES %I.debug_sessions(session_id) ON DELETE CASCADE',
            target_schema,
            target_schema
        );
    END IF;
END $$;
