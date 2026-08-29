package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable copy of an accepted capture batch used to recover post-commit shadow delivery. */
@Entity
@Table(name = "routine_capture_batches", indexes = {
    @Index(name = "idx_routine_capture_batch_source", columnList = "session_id,kind,sequence", unique = true),
    @Index(name = "idx_routine_capture_batch_session", columnList = "session_id,accepted_at")
})
public class RoutineCaptureBatch {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "session_id", nullable = false, length = 160) private String sessionId;
  @Column(nullable = false, length = 20) private String kind;
  @Column(nullable = false) private int sequence;
  @Column(name = "values_json", nullable = false, columnDefinition = "text") private String valuesJson;
  @Column(name = "accepted_at", nullable = false) private Instant acceptedAt = Instant.now();

  public Long getId() { return id; }
  public String getSessionId() { return sessionId; }
  public void setSessionId(String value) { sessionId = value; }
  public String getKind() { return kind; }
  public void setKind(String value) { kind = value; }
  public int getSequence() { return sequence; }
  public void setSequence(int value) { sequence = value; }
  public String getValuesJson() { return valuesJson; }
  public void setValuesJson(String value) { valuesJson = value; }
  public Instant getAcceptedAt() { return acceptedAt; }
  public void setAcceptedAt(Instant value) { acceptedAt = value; }
}
