-- SC-1173: do not amend V47. These additive corrections make public IDs the durable
-- JPA identity and prevent cross-incident lineage from being represented in PostgreSQL.

ALTER TABLE builder_goal_recovery_incident
    ADD COLUMN IF NOT EXISTS entity_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE micro6_patch_proposal
    ADD COLUMN IF NOT EXISTS entity_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE micro6_patch_approval
    ADD COLUMN IF NOT EXISTS entity_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE builder_goal_candidate_rerun
    ADD COLUMN IF NOT EXISTS entity_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE builder_goal_recovery_artifact
    ADD CONSTRAINT uq_bg_recovery_artifact_identity
    UNIQUE (incident_id, kind, sha256, sanitization_version);

-- Parent composite keys are deliberately explicit: a child must agree on every parent
-- identity it carries, not merely reference independently valid rows.
ALTER TABLE micro6_patch_approval
    ADD CONSTRAINT uq_micro6_patch_approval_id_proposal UNIQUE (approval_id, proposal_id);
ALTER TABLE micro6_patch_validation_job
    ADD CONSTRAINT uq_micro6_patch_validation_job_id_proposal_incident
    UNIQUE (job_id, proposal_id, incident_id);
ALTER TABLE micro6_candidate_artifact
    ADD CONSTRAINT uq_micro6_candidate_id_incident UNIQUE (candidate_id, incident_id);

ALTER TABLE micro6_patch_validation_job
    ADD CONSTRAINT fk_bg_validation_proposal_incident
    FOREIGN KEY (proposal_id, incident_id)
    REFERENCES micro6_patch_proposal (proposal_id, incident_id),
    ADD CONSTRAINT fk_bg_validation_approval_proposal
    FOREIGN KEY (approval_id, proposal_id)
    REFERENCES micro6_patch_approval (approval_id, proposal_id);

ALTER TABLE micro6_candidate_artifact
    ADD CONSTRAINT fk_bg_candidate_job_incident
    FOREIGN KEY (job_id, incident_id)
    REFERENCES micro6_patch_validation_job (job_id, incident_id),
    ADD CONSTRAINT fk_bg_candidate_job_proposal
    FOREIGN KEY (job_id, proposal_id)
    REFERENCES micro6_patch_validation_job (job_id, proposal_id),
    ADD CONSTRAINT fk_bg_candidate_proposal_incident
    FOREIGN KEY (proposal_id, incident_id)
    REFERENCES micro6_patch_proposal (proposal_id, incident_id);

ALTER TABLE builder_goal_candidate_rerun
    ADD CONSTRAINT fk_bg_rerun_candidate_incident
    FOREIGN KEY (candidate_id, incident_id)
    REFERENCES micro6_candidate_artifact (candidate_id, incident_id);

ALTER TABLE builder_goal_recovery_audit_event
    ADD CONSTRAINT fk_bg_recovery_audit_incident
    FOREIGN KEY (incident_id)
    REFERENCES builder_goal_recovery_incident (incident_id);

-- Validation idempotency is scoped to its parent incident. A global key made unrelated
-- recovery cycles conflict and permitted a replay to resolve the wrong lineage.
ALTER TABLE micro6_patch_validation_job
    DROP CONSTRAINT uq_micro6_patch_validation_job_idempotency,
    ADD CONSTRAINT uq_micro6_patch_validation_job_incident_idempotency
    UNIQUE (incident_id, idempotency_key);
