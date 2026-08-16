package com.chromascape.web.accounttask.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One verified stabilization artifact stored under an opaque UUID id. Never stores host paths;
 * the bytes live in the filesystem artifact store and are retrievable only through the artifact
 * id.
 *
 * <p>SC-572: every verified artifact carries a nonblank run id and a durable external evidence
 * reference, and {@code (run_id, external_reference)} is unique. The constraints are enforced by
 * a versioned Flyway migration (never by {@code ddl-auto: update}); this entity deliberately does
 * not ask Hibernate to create or alter those indexes.
 */
@Entity
@Table(name = "stabilization_artifact")
public class StabilizationArtifact {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // Uniqueness is deliberately owned by the Flyway migration rather than Hibernate ddl-auto.
  @Column(nullable = false, length = 36)
  private String artifactId;

  @Column(nullable = false, length = 100)
  private String runId;

  @Column(length = 100)
  private String attemptId;

  /** Durable external evidence reference (e.g. {@code artifact://debug-recorder/log-...}) bound to
   *  the stored artifact UUID so the contract's evidence-reference → artifact-UUID mapping is
   *  auditable without reconstructing a checksum lookup (SC-572). Non-null and unique per run. */
  @Column(nullable = false, length = 512)
  private String externalReference;

  @Column(length = 50)
  private String evidenceType;

  @Column(length = 200)
  private String sha256;

  @Column
  private Long sizeBytes;

  @Column(length = 100)
  private String mediaType;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  public StabilizationArtifact() {
    this.createdAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public String getArtifactId() {
    return artifactId;
  }

  public void setArtifactId(UUID artifactId) {
    this.artifactId = artifactId.toString();
  }

  public void setArtifactId(String artifactId) {
    this.artifactId = artifactId;
  }

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public String getAttemptId() {
    return attemptId;
  }

  public void setAttemptId(String attemptId) {
    this.attemptId = attemptId;
  }

  public String getExternalReference() {
    return externalReference;
  }

  public void setExternalReference(String externalReference) {
    this.externalReference = externalReference;
  }

  public String getEvidenceType() {
    return evidenceType;
  }

  public void setEvidenceType(String evidenceType) {
    this.evidenceType = evidenceType;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String sha256) {
    this.sha256 = sha256;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(Long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public String getMediaType() {
    return mediaType;
  }

  public void setMediaType(String mediaType) {
    this.mediaType = mediaType;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
