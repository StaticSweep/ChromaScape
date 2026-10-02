-- SC-641: immutable catalog/profile snapshots. Micro6 remains task/schema authority.
CREATE TABLE IF NOT EXISTS ai_player_behavior_profiles (
    profile_id VARCHAR(160) NOT NULL,
    profile_version INTEGER NOT NULL CHECK (profile_version > 0),
    task_key VARCHAR(160) NOT NULL CHECK (btrim(task_key) <> ''),
    catalog_hash VARCHAR(71) NOT NULL CHECK (catalog_hash ~ '^sha256:[0-9a-f]{64}$'),
    settings_json JSONB NOT NULL CHECK (jsonb_typeof(settings_json) = 'object'),
    settings_hash VARCHAR(71) NOT NULL CHECK (settings_hash ~ '^sha256:[0-9a-f]{64}$'),
    requirements_json JSONB NOT NULL CHECK (jsonb_typeof(requirements_json) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (profile_id,profile_version)
);
ALTER TABLE ai_player_behavior_profiles ADD COLUMN IF NOT EXISTS name VARCHAR(120);
ALTER TABLE ai_player_behavior_profiles ADD COLUMN IF NOT EXISTS catalog_version VARCHAR(160);
ALTER TABLE ai_player_behavior_profiles ADD COLUMN IF NOT EXISTS schema_hash VARCHAR(71);
ALTER TABLE ai_player_behavior_profiles ADD COLUMN IF NOT EXISTS requirements_hash VARCHAR(71);
ALTER TABLE ai_player_behavior_profiles ADD COLUMN IF NOT EXISTS catalog_snapshot JSONB;
ALTER TABLE ai_player_behavior_profiles ADD COLUMN IF NOT EXISTS task_snapshot JSONB;
ALTER TABLE ai_player_behavior_profiles ADD COLUMN IF NOT EXISTS schema_snapshot JSONB;
ALTER TABLE ai_player_behavior_profiles ADD COLUMN IF NOT EXISTS source_summary JSONB;
ALTER TABLE ai_player_behavior_profiles ADD COLUMN IF NOT EXISTS method_facts JSONB;
ALTER TABLE ai_player_behavior_profiles ADD CONSTRAINT sc641_profile_snapshot_shape CHECK (
    name IS NULL OR (
        btrim(name) <> '' AND catalog_version IS NOT NULL
        AND schema_hash IS NOT NULL AND schema_hash ~ '^sha256:[0-9a-f]{64}$'
        AND requirements_hash IS NOT NULL AND requirements_hash ~ '^sha256:[0-9a-f]{64}$' AND catalog_snapshot IS NOT NULL
        AND task_snapshot IS NOT NULL AND schema_snapshot IS NOT NULL AND source_summary IS NOT NULL
        AND method_facts IS NOT NULL AND jsonb_typeof(catalog_snapshot)='object'
        AND jsonb_typeof(task_snapshot)='object' AND jsonb_typeof(schema_snapshot)='object'
        AND jsonb_typeof(source_summary)='object' AND jsonb_typeof(method_facts)='object'
        AND task_snapshot ? 'task_key' AND catalog_snapshot ? 'catalog_hash'
        AND task_snapshot->>'task_key'=task_key AND catalog_snapshot->>'catalog_hash'=catalog_hash
    )
);
CREATE INDEX sc641_profiles_recent ON ai_player_behavior_profiles(created_at DESC,profile_id,profile_version DESC);
CREATE TABLE ai_player_catalog_snapshots (
    snapshot_id VARCHAR(71) NOT NULL CHECK (snapshot_id ~ '^sha256:[0-9a-f]{64}$'),
    endpoint_ref VARCHAR(200) NOT NULL,
    mode VARCHAR(20) NOT NULL CHECK (mode IN ('lightweight','full')),
    catalog_hash VARCHAR(71) NOT NULL CHECK (catalog_hash ~ '^sha256:[0-9a-f]{64}$'),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='object'),
    captured_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(endpoint_ref,mode,snapshot_id)
);
CREATE INDEX sc641_catalog_latest ON ai_player_catalog_snapshots(endpoint_ref,mode,captured_at DESC,snapshot_id DESC);
CREATE FUNCTION sc641_append_only() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'SC641_IMMUTABLE: %',TG_TABLE_NAME USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER sc641_profile_immutable BEFORE UPDATE OR DELETE ON ai_player_behavior_profiles
    FOR EACH ROW EXECUTE FUNCTION sc641_append_only();
CREATE TRIGGER sc641_catalog_immutable BEFORE UPDATE OR DELETE ON ai_player_catalog_snapshots
    FOR EACH ROW EXECUTE FUNCTION sc641_append_only();

-- A new current disposable contract requires reviewed reset, never an automatic upgrade.
ALTER TABLE ai_player_schema_identity DROP CONSTRAINT ai_player_schema_identity_identity_check;
ALTER TABLE ai_player_schema_identity DISABLE TRIGGER ai_player_identity_immutable;
UPDATE ai_player_schema_identity SET identity='sc641-current-v1';
ALTER TABLE ai_player_schema_identity ENABLE TRIGGER ai_player_identity_immutable;
ALTER TABLE ai_player_schema_identity ADD CONSTRAINT ai_player_schema_identity_identity_check CHECK (identity='sc641-current-v1');
