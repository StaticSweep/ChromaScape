package com.chromascape.web.debug.buildergoal.recovery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only audit event for every recovery action and denial. Rows are never updated. */
@Entity
@Table(name = "builder_goal_recovery_audit_event")
public class BuilderGoalRecoveryAuditEvent {
  @Id
  @Column(name = "audit_event_id", nullable = false, length = 80)
  private String auditEventId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "actor_id", length = 160)
  private String actorId;

  @Column(name = "actor_role", length = 60)
  private String actorRole;

  @Column(nullable = false, length = 80)
  private String action;

  @Column(name = "iteration_id", length = 100)
  private String iterationId;

  @Column(name = "incident_id", length = 80)
  private String incidentId;

  @Column(name = "subject_type", length = 60)
  private String subjectType;

  @Column(name = "subject_id", length = 160)
  private String subjectId;

  @Column(nullable = false, length = 20)
  private String outcome;

  @Column(name = "error_code", length = 100)
  private String errorCode;

  @Column(name = "detail_json", columnDefinition = "text")
  private String detailJson;

  public String getAuditEventId() {
    return auditEventId;
  }

  public void setAuditEventId(String value) {
    auditEventId = value;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(Instant value) {
    occurredAt = value;
  }

  public String getActorId() {
    return actorId;
  }

  public void setActorId(String value) {
    actorId = value;
  }

  public String getActorRole() {
    return actorRole;
  }

  public void setActorRole(String value) {
    actorRole = value;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String value) {
    action = value;
  }

  public String getIterationId() {
    return iterationId;
  }

  public void setIterationId(String value) {
    iterationId = value;
  }

  public String getIncidentId() {
    return incidentId;
  }

  public void setIncidentId(String value) {
    incidentId = value;
  }

  public String getSubjectType() {
    return subjectType;
  }

  public void setSubjectType(String value) {
    subjectType = value;
  }

  public String getSubjectId() {
    return subjectId;
  }

  public void setSubjectId(String value) {
    subjectId = value;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String value) {
    outcome = value;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String value) {
    errorCode = value;
  }

  public String getDetailJson() {
    return detailJson;
  }

  public void setDetailJson(String value) {
    detailJson = value;
  }
}
