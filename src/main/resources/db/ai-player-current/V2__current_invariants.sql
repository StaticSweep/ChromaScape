-- SC-665 database boundary: this is a clean disposable schema, not a legacy upgrade.
CREATE TABLE ai_player_schema_identity (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    identity TEXT NOT NULL CHECK (identity = 'sc665-current-v1')
);
CREATE TABLE ai_player_behavior_profiles (
    profile_id VARCHAR(160) NOT NULL,
    profile_version INTEGER NOT NULL CHECK (profile_version > 0),
    task_key VARCHAR(160) NOT NULL CHECK (btrim(task_key) <> ''),
    catalog_hash VARCHAR(71) NOT NULL CHECK (catalog_hash ~ '^sha256:[0-9a-f]{64}$'),
    settings_json JSONB NOT NULL CHECK (jsonb_typeof(settings_json) = 'object'),
    settings_hash VARCHAR(71) NOT NULL CHECK (settings_hash ~ '^sha256:[0-9a-f]{64}$'),
    requirements_json JSONB NOT NULL CHECK (jsonb_typeof(requirements_json) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (profile_id, profile_version)
);
CREATE TABLE ai_player_run_bindings (
    run_id VARCHAR(160) PRIMARY KEY REFERENCES current_slice_runs(run_id),
    profile_id VARCHAR(160) NOT NULL,
    profile_version INTEGER NOT NULL,
    client_id VARCHAR(160) NOT NULL CHECK (btrim(client_id) <> ''),
    account_id VARCHAR(160) NOT NULL CHECK (btrim(account_id) <> ''),
    session_id VARCHAR(160) NOT NULL UNIQUE REFERENCES routine_capture_records(session_id),
    debug_session_id VARCHAR(100) NOT NULL UNIQUE REFERENCES debug_sessions(session_id),
    recording_id VARCHAR(100) NOT NULL UNIQUE REFERENCES recording_sessions(recording_id),
    FOREIGN KEY (profile_id, profile_version)
        REFERENCES ai_player_behavior_profiles(profile_id, profile_version),
    UNIQUE (run_id, session_id, debug_session_id, recording_id)
);
CREATE TABLE ai_player_datasets (
    dataset_id VARCHAR(160) PRIMARY KEY,
    content_hash VARCHAR(71) NOT NULL CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
    manifest_json JSONB NOT NULL CHECK (jsonb_typeof(manifest_json) = 'object'),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE ai_player_jobs (
    job_id VARCHAR(160) PRIMARY KEY,
    run_id VARCHAR(160) NOT NULL REFERENCES current_slice_runs(run_id),
    request_hash VARCHAR(71) NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    state VARCHAR(30) NOT NULL CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    result_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CHECK ((state IN ('SUCCEEDED','FAILED','CANCELLED')) = (completed_at IS NOT NULL)),
    CHECK (state <> 'SUCCEEDED' OR result_json IS NOT NULL)
);
CREATE INDEX ai_player_jobs_claim ON ai_player_jobs(state, created_at, job_id);

-- Common append-only evidence rule; exception leaves the surrounding transaction uncommitted.
CREATE FUNCTION ai_player_append_only() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'AI_PLAYER_IMMUTABLE: %', TG_TABLE_NAME USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER ai_player_profile_immutable BEFORE UPDATE OR DELETE ON ai_player_behavior_profiles
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();
CREATE TRIGGER ai_player_binding_immutable BEFORE UPDATE OR DELETE ON ai_player_run_bindings
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();
CREATE TRIGGER ai_player_dataset_immutable BEFORE UPDATE OR DELETE ON ai_player_datasets
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();
CREATE TRIGGER ai_player_identity_immutable BEFORE UPDATE OR DELETE ON ai_player_schema_identity
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();

ALTER TABLE current_slice_runs ADD CONSTRAINT ai_player_control_off CHECK (control_allowed = false);
ALTER TABLE routine_assistant_conversations ADD CONSTRAINT ai_player_conversation_run_fk
    FOREIGN KEY (run_id) REFERENCES current_slice_runs(run_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE routine_assistant_events ADD CONSTRAINT ai_player_event_conversation_fk
    FOREIGN KEY (conversation_id) REFERENCES routine_assistant_conversations(conversation_id);
ALTER TABLE routine_assistant_proposals ADD CONSTRAINT ai_player_proposal_conversation_fk
    FOREIGN KEY (conversation_id) REFERENCES routine_assistant_conversations(conversation_id);
ALTER TABLE routine_assistant_events ADD CONSTRAINT ai_player_event_nonnegative CHECK (event_id >= 0);
ALTER TABLE routine_models ADD CONSTRAINT ai_player_model_graph_fk
    FOREIGN KEY (graph_id) REFERENCES behavior_graph_definitions(graph_id);
ALTER TABLE routine_replay_traces ADD CONSTRAINT ai_player_trace_model_fk
    FOREIGN KEY (model_id) REFERENCES routine_models(model_id);
ALTER TABLE routine_replay_traces ADD CONSTRAINT ai_player_trace_capture_fk
    FOREIGN KEY (recording_id) REFERENCES routine_capture_records(recording_id);
ALTER TABLE routine_curations ADD CONSTRAINT ai_player_curation_capture_fk
    FOREIGN KEY (recording_id) REFERENCES routine_capture_records(recording_id);
ALTER TABLE routine_capture_operations ADD CONSTRAINT ai_player_operation_capture_fk
    FOREIGN KEY (session_id) REFERENCES routine_capture_records(session_id);
ALTER TABLE routine_capture_batches ADD CONSTRAINT ai_player_batch_capture_fk
    FOREIGN KEY (session_id) REFERENCES routine_capture_records(session_id);
ALTER TABLE routine_capture_external_bindings ADD CONSTRAINT ai_player_external_capture_fk
    FOREIGN KEY (session_id) REFERENCES routine_capture_records(session_id);
ALTER TABLE routine_capture_records ADD CONSTRAINT ai_player_capture_completion_shape CHECK (
    completed = (completion_status = 'COMPLETED') AND
    (NOT completed OR (final_tick IS NOT NULL AND final_tick >= start_tick
        AND recording_id IS NOT NULL AND btrim(recording_id) <> ''
        AND debug_session_id IS NOT NULL AND btrim(debug_session_id) <> ''
        AND recording_hash ~ '^sha256:[0-9a-f]{64}$'
        AND video_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND completion_request_hash ~ '^sha256:[0-9a-f]{64}$'
        AND recording_hash IS NOT NULL AND video_sha256 IS NOT NULL
        AND completion_request_hash IS NOT NULL))
);
ALTER TABLE routine_capture_records ADD CONSTRAINT ai_player_capture_sequences
    CHECK (next_tick_sequence >= 1 AND next_action_sequence >= 1);
CREATE UNIQUE INDEX ai_player_capture_identity_tuple
    ON routine_capture_records(session_id, debug_session_id, recording_id);
CREATE UNIQUE INDEX ai_player_debug_recording_tuple ON debug_sessions(session_id, recording_id);
ALTER TABLE ai_player_run_bindings ADD CONSTRAINT ai_player_bound_capture_tuple_fk
    FOREIGN KEY (session_id, debug_session_id, recording_id)
    REFERENCES routine_capture_records(session_id, debug_session_id, recording_id)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE ai_player_run_bindings ADD CONSTRAINT ai_player_bound_debug_tuple_fk
    FOREIGN KEY (debug_session_id, recording_id)
    REFERENCES debug_sessions(session_id, recording_id) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION ai_player_graph_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state='PUBLISHED' THEN
        RAISE EXCEPTION 'AI_PLAYER_IMMUTABLE: published graph' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ai_player_published_graph_immutable BEFORE UPDATE OR DELETE ON behavior_graph_definitions
    FOR EACH ROW EXECUTE FUNCTION ai_player_graph_immutable();
CREATE TRIGGER ai_player_model_immutable BEFORE UPDATE OR DELETE ON routine_models
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();
CREATE TRIGGER ai_player_trace_immutable BEFORE UPDATE OR DELETE ON routine_replay_traces
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();
CREATE TRIGGER ai_player_assistant_event_immutable BEFORE UPDATE OR DELETE ON routine_assistant_events
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();
CREATE TRIGGER ai_player_capture_batch_immutable BEFORE UPDATE OR DELETE ON routine_capture_batches
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();

CREATE FUNCTION ai_player_capture_identity_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.completed THEN
            RAISE EXCEPTION 'AI_PLAYER_IMMUTABLE: finalized capture' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.completed OR NEW.session_id IS DISTINCT FROM OLD.session_id
        OR NEW.start_idempotency_key IS DISTINCT FROM OLD.start_idempotency_key
        OR NEW.start_request_hash IS DISTINCT FROM OLD.start_request_hash
        OR NEW.identity_json IS DISTINCT FROM OLD.identity_json
        OR NEW.started_at IS DISTINCT FROM OLD.started_at
        OR (OLD.recording_id IS NOT NULL AND NEW.recording_id IS DISTINCT FROM OLD.recording_id)
        OR (OLD.debug_session_id IS NOT NULL AND NEW.debug_session_id IS DISTINCT FROM OLD.debug_session_id)
    THEN
        RAISE EXCEPTION 'AI_PLAYER_IMMUTABLE: capture identity/finalization' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ai_player_capture_identity BEFORE UPDATE OR DELETE ON routine_capture_records
    FOR EACH ROW EXECUTE FUNCTION ai_player_capture_identity_guard();

-- Completion depends on the exact same finalized physical recording/debug session, checked at
-- COMMIT. Finalize physical rows before marking the capture completed in the same transaction.
CREATE FUNCTION ai_player_capture_finalization_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.completed AND NOT EXISTS (
        SELECT 1 FROM recording_sessions r JOIN debug_sessions d ON d.recording_id=r.recording_id
        WHERE r.recording_id=NEW.recording_id AND d.session_id=NEW.debug_session_id
          AND r.end_time IS NOT NULL AND d.end_time IS NOT NULL AND d.end_tick=NEW.final_tick
    ) THEN
        RAISE EXCEPTION 'AI_PLAYER_INCOMPLETE: recording/debug evidence not finalized'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER ai_player_capture_finalization AFTER INSERT OR UPDATE ON routine_capture_records
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ai_player_capture_finalization_guard();

CREATE FUNCTION ai_player_terminal_job_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state IN ('SUCCEEDED','FAILED','CANCELLED') THEN
        RAISE EXCEPTION 'AI_PLAYER_IMMUTABLE: terminal job' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NEW.job_id IS DISTINCT FROM OLD.job_id OR NEW.run_id IS DISTINCT FROM OLD.run_id
        OR NEW.request_hash IS DISTINCT FROM OLD.request_hash THEN
        RAISE EXCEPTION 'AI_PLAYER_IMMUTABLE: job identity' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ai_player_terminal_job BEFORE UPDATE OR DELETE ON ai_player_jobs
    FOR EACH ROW EXECUTE FUNCTION ai_player_terminal_job_guard();

-- Finalized capture identity cannot be invalidated by reopening its physical source rows.
CREATE FUNCTION ai_player_physical_evidence_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM routine_capture_records c WHERE c.completed AND
        ((TG_TABLE_NAME='recording_sessions' AND c.recording_id=to_jsonb(OLD)->>'recording_id')
         OR (TG_TABLE_NAME='debug_sessions' AND c.debug_session_id=to_jsonb(OLD)->>'session_id'))) THEN
        IF TG_OP='DELETE' OR
          (to_jsonb(NEW) - ARRAY['replay_name','replay_tags_json']) IS DISTINCT FROM
          (to_jsonb(OLD) - ARRAY['replay_name','replay_tags_json']) THEN
            RAISE EXCEPTION 'AI_PLAYER_IMMUTABLE: finalized physical evidence' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ai_player_recording_evidence BEFORE UPDATE OR DELETE ON recording_sessions
    FOR EACH ROW EXECUTE FUNCTION ai_player_physical_evidence_guard();
CREATE TRIGGER ai_player_debug_evidence BEFORE UPDATE OR DELETE ON debug_sessions
    FOR EACH ROW EXECUTE FUNCTION ai_player_physical_evidence_guard();

CREATE FUNCTION ai_player_binding_identity_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE run_identity JSONB; capture_identity JSONB; profile ai_player_behavior_profiles%ROWTYPE;
BEGIN
    SELECT identity_json::jsonb INTO run_identity FROM current_slice_runs WHERE run_id=NEW.run_id;
    SELECT identity_json::jsonb INTO capture_identity FROM routine_capture_records WHERE session_id=NEW.session_id;
    SELECT * INTO profile FROM ai_player_behavior_profiles
        WHERE profile_id=NEW.profile_id AND profile_version=NEW.profile_version;
    IF run_identity #>> '{run,run_id}' IS DISTINCT FROM NEW.run_id
        OR capture_identity #>> '{run,run_id}' IS DISTINCT FROM NEW.run_id
        OR run_identity #>> '{client,client_id}' IS DISTINCT FROM NEW.client_id
        OR capture_identity #>> '{client,client_id}' IS DISTINCT FROM NEW.client_id
        OR run_identity->'task' IS DISTINCT FROM capture_identity->'task'
        OR run_identity #>> '{task,profile_id}' IS DISTINCT FROM NEW.profile_id
        OR run_identity #>> '{task,task_key}' IS DISTINCT FROM profile.task_key
        OR run_identity #>> '{task,catalog_hash}' IS DISTINCT FROM profile.catalog_hash
        OR run_identity #>> '{task,settings_hash}' IS DISTINCT FROM profile.settings_hash
    THEN
        RAISE EXCEPTION 'AI_PLAYER_IDENTITY_MISMATCH: run/client/profile binding' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER ai_player_binding_identity AFTER INSERT ON ai_player_run_bindings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ai_player_binding_identity_guard();

CREATE FUNCTION ai_player_run_identity_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NEW.run_id IS DISTINCT FROM OLD.run_id
        OR NEW.identity_json::jsonb->'run' IS DISTINCT FROM OLD.identity_json::jsonb->'run'
        OR NEW.identity_json::jsonb->'client' IS DISTINCT FROM OLD.identity_json::jsonb->'client'
        OR NEW.identity_json::jsonb->'task' IS DISTINCT FROM OLD.identity_json::jsonb->'task'
    THEN
        RAISE EXCEPTION 'AI_PLAYER_IMMUTABLE: run identity' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ai_player_run_identity BEFORE UPDATE ON current_slice_runs
    FOR EACH ROW EXECUTE FUNCTION ai_player_run_identity_guard();
ALTER TABLE current_slice_runs ADD CONSTRAINT ai_player_run_json_identity
    CHECK (identity_json::jsonb #>> '{run,run_id}' IS NOT NULL
       AND identity_json::jsonb #>> '{run,run_id}' = run_id);
ALTER TABLE recording_sessions ADD CONSTRAINT ai_player_recording_time_order
    CHECK (end_time IS NULL OR end_time >= start_time);
ALTER TABLE debug_sessions ADD CONSTRAINT ai_player_debug_time_order
    CHECK ((end_time IS NULL OR end_time >= start_time) AND (end_tick IS NULL OR end_tick >= start_tick));

CREATE TRIGGER ai_player_source_audit_immutable BEFORE UPDATE OR DELETE ON source_read_audit_events
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();
CREATE FUNCTION ai_player_registered_candidate_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state='REGISTERED' THEN
        RAISE EXCEPTION 'AI_PLAYER_IMMUTABLE: registered candidate' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ai_player_registered_candidate BEFORE UPDATE OR DELETE ON patch_candidate_runs
    FOR EACH ROW EXECUTE FUNCTION ai_player_registered_candidate_guard();
ALTER TABLE patch_candidate_runs ADD CONSTRAINT ai_player_registration_complete CHECK (
    state <> 'REGISTERED' OR (
        candidate_id IS NOT NULL AND candidate_hash ~ '^sha256:[0-9a-f]{64}$'
        AND candidate_hash IS NOT NULL AND approval_hash IS NOT NULL AND approved_at IS NOT NULL
        AND manifest_file_hash IS NOT NULL AND jar_sha256 IS NOT NULL
        AND sbom_sha256 IS NOT NULL AND certification_document_hash IS NOT NULL
        AND registry_response_hash IS NOT NULL AND registry_verified_at IS NOT NULL
        AND final_identity_json IS NOT NULL AND result_json IS NOT NULL
    )
);

-- Model and trace lineage must retain the originating run/client/task envelope.
CREATE FUNCTION ai_player_lineage_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent_identity JSONB; capture_identity JSONB;
BEGIN
    IF TG_TABLE_NAME='routine_models' THEN
        SELECT identity_json::jsonb INTO parent_identity FROM behavior_graph_definitions WHERE graph_id=NEW.graph_id;
    ELSE
        SELECT identity_json::jsonb INTO parent_identity FROM routine_models WHERE model_id=NEW.model_id;
        SELECT identity_json::jsonb INTO capture_identity FROM routine_capture_records WHERE recording_id=NEW.recording_id;
    END IF;
    -- Missing parents are rejected by the corresponding FK, preserving SQLSTATE 23503.
    IF parent_identity IS NOT NULL AND (
        NEW.identity_json::jsonb->'run' IS DISTINCT FROM parent_identity->'run'
        OR NEW.identity_json::jsonb->'client' IS DISTINCT FROM parent_identity->'client'
        OR NEW.identity_json::jsonb->'task' IS DISTINCT FROM parent_identity->'task') THEN
        RAISE EXCEPTION 'AI_PLAYER_IDENTITY_MISMATCH: artifact lineage' USING ERRCODE='23514';
    END IF;
    IF capture_identity IS NOT NULL AND (
        NEW.identity_json::jsonb->'run' IS DISTINCT FROM capture_identity->'run'
        OR NEW.identity_json::jsonb->'client' IS DISTINCT FROM capture_identity->'client'
        OR NEW.identity_json::jsonb->'task' IS DISTINCT FROM capture_identity->'task') THEN
        RAISE EXCEPTION 'AI_PLAYER_IDENTITY_MISMATCH: trace/capture lineage' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ai_player_model_lineage BEFORE INSERT ON routine_models
    FOR EACH ROW EXECUTE FUNCTION ai_player_lineage_guard();
CREATE TRIGGER ai_player_trace_lineage BEFORE INSERT ON routine_replay_traces
    FOR EACH ROW EXECUTE FUNCTION ai_player_lineage_guard();
CREATE TRIGGER ai_player_source_manifest_no_delete BEFORE DELETE ON source_manifest_references
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();
CREATE TRIGGER ai_player_source_content_no_delete BEFORE DELETE ON source_content_objects
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();
CREATE TRIGGER ai_player_source_binding_no_delete BEFORE DELETE ON source_run_bindings
    FOR EACH ROW EXECUTE FUNCTION ai_player_append_only();
