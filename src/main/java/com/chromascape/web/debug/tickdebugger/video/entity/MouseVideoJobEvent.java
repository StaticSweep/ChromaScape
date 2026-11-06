package com.chromascape.web.debug.tickdebugger.video.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable job transition/event record; retains generation and bounded metadata only. */
@Entity
@Table(name = "mouse_video_job_events")
public class MouseVideoJobEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "job_id", columnDefinition = "uuid", nullable = false)
  private UUID jobId;

  @Column(name = "generation", nullable = false)
  private long generation;

  @Column(name = "event_type", nullable = false, length = 48)
  private String eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata_json")
  private JsonNode metadataJson;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected MouseVideoJobEvent() {}

  public MouseVideoJobEvent(UUID jobId, long generation, String eventType, JsonNode metadataJson) {
    this.jobId = jobId;
    this.generation = generation;
    this.eventType = eventType;
    this.metadataJson = metadataJson;
    this.createdAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public UUID getJobId() {
    return jobId;
  }

  public long getGeneration() {
    return generation;
  }

  public String getEventType() {
    return eventType;
  }

  public JsonNode getMetadataJson() {
    return metadataJson;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
