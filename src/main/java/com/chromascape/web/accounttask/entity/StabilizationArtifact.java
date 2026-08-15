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
 */
@Entity
@Table(name = "stabilization_artifact")
public class StabilizationArtifact {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 36)
  private String artifactId;

  @Column(length = 100)
  private String runId;

  @Column(length = 100)
  private String attemptId;

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
