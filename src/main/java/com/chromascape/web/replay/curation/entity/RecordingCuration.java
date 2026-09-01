package com.chromascape.web.replay.curation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "recording_curations")
public class RecordingCuration {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "recording_id", nullable = false)
  private String recordingId;

  @Column(name = "debug_session_id", nullable = false)
  private String debugSessionId;

  @Column(nullable = false)
  private int version;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private CurationState state;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private CurationRole role;

  @Column(name = "schema_version", nullable = false)
  private int schemaVersion = 1;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "content_json", nullable = false)
  private JsonNode contentJson;

  @Column(name = "content_checksum", nullable = false)
  private String contentChecksum;

  @Column(name = "created_by", nullable = false)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  public RecordingCuration() {}

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
  }

  public String getDebugSessionId() {
    return debugSessionId;
  }

  public void setDebugSessionId(String debugSessionId) {
    this.debugSessionId = debugSessionId;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public CurationState getState() {
    return state;
  }

  public void setState(CurationState state) {
    this.state = state;
  }

  public CurationRole getRole() {
    return role;
  }

  public void setRole(CurationRole role) {
    this.role = role;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(int schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public JsonNode getContentJson() {
    return contentJson;
  }

  public void setContentJson(JsonNode contentJson) {
    this.contentJson = contentJson;
  }

  public String getContentChecksum() {
    return contentChecksum;
  }

  public void setContentChecksum(String contentChecksum) {
    this.contentChecksum = contentChecksum;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getPublishedAt() {
    return publishedAt;
  }

  public void setPublishedAt(LocalDateTime publishedAt) {
    this.publishedAt = publishedAt;
  }

  public boolean isPublished() {
    return state == CurationState.PUBLISHED;
  }

  /** Publication stamp must be supplied from the PostgreSQL clock by the service layer. */
  public void publish(LocalDateTime publishedAt) {
    this.state = CurationState.PUBLISHED;
    this.publishedAt = java.util.Objects.requireNonNull(publishedAt, "publishedAt");
  }
}
