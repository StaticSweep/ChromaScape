package com.chromascape.web.debug.buildergoal.recovery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "builder_goal_recovery_incident")
public class BuilderGoalRecoveryIncident {
  @Id
  @Column(name = "incident_id", nullable = false, length = 80)
  private String incidentId;

  @Column(name = "iteration_id", nullable = false, length = 100)
  private String iterationId;

  @Column(name = "incident_type", nullable = false, length = 40)
  private String incidentType;

  @Column(name = "failure_code", nullable = false, length = 100)
  private String failureCode;

  @Column(name = "correlation_id", nullable = false, length = 160)
  private String correlationId;

  @Column(name = "run_identity_json", nullable = false, columnDefinition = "text")
  private String runIdentityJson;

  @Column(name = "evidence_manifest_json", columnDefinition = "text")
  private String evidenceManifestJson;

  @Column(name = "evidence_manifest_hash", length = 71)
  private String evidenceManifestHash;

  @Column(nullable = false, length = 40)
  private String state;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Version
  @Column(name = "entity_version", nullable = false)
  private Long version;

  public String getIncidentId() {
    return incidentId;
  }

  public void setIncidentId(String value) {
    incidentId = value;
  }

  public String getIterationId() {
    return iterationId;
  }

  public void setIterationId(String value) {
    iterationId = value;
  }

  public String getIncidentType() {
    return incidentType;
  }

  public void setIncidentType(String value) {
    incidentType = value;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public void setFailureCode(String value) {
    failureCode = value;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(String value) {
    correlationId = value;
  }

  public String getRunIdentityJson() {
    return runIdentityJson;
  }

  public void setRunIdentityJson(String value) {
    runIdentityJson = value;
  }

  public String getEvidenceManifestJson() {
    return evidenceManifestJson;
  }

  public void setEvidenceManifestJson(String value) {
    evidenceManifestJson = value;
  }

  public String getEvidenceManifestHash() {
    return evidenceManifestHash;
  }

  public void setEvidenceManifestHash(String value) {
    evidenceManifestHash = value;
  }

  public String getState() {
    return state;
  }

  public void setState(String value) {
    state = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    createdAt = value;
  }
}
