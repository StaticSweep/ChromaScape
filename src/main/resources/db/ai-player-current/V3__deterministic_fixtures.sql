INSERT INTO ai_player_schema_identity(singleton, identity) VALUES (TRUE, 'sc665-current-v1');
INSERT INTO ai_player_behavior_profiles(profile_id, profile_version, task_key, catalog_hash,
    settings_json, settings_hash, requirements_json, created_at)
VALUES ('sc665-fishing-fixture', 1, 'FISHING_TRAINING',
    'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    '{}', 'sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a',
    '{"fixtureOnly":true,"controlAllowed":false}', '2026-10-02T00:00:00Z');
-- Deliberately no live account, execution authority, finalized recording, model or passing
-- certification is seeded. This fixture describes schema shape only, not available execution.
