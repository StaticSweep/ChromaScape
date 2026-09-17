package com.chromascape.web.debug.tickdebugger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;

/** Durable identity of one accepted tick-batch delivery. */
@Entity
@Table(
    name = "replay_tick_batch_receipts",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_replay_tick_batch_receipt_session_batch",
          columnNames = {"session_id", "batch_id"}),
      @UniqueConstraint(
          name = "uq_replay_tick_batch_receipt_session_tick",
          columnNames = {"session_id", "tick"})
    })
public class ReplayTickBatchReceipt {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "session_id", nullable = false, length = 100)
  private String sessionId;

  @Column(name = "batch_id", nullable = false)
  private UUID batchId;

  @Column(nullable = false)
  private long tick;

  @Column(name = "payload_checksum", nullable = false, length = 71)
  private String payloadChecksum;

  @Column(name = "schema_name", nullable = false, length = 64)
  private String schemaName;

  @Column(name = "persisted_at", nullable = false)
  private LocalDateTime persistedAt;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public UUID getBatchId() {
    return batchId;
  }

  public void setBatchId(UUID batchId) {
    this.batchId = batchId;
  }

  public long getTick() {
    return tick;
  }

  public void setTick(long tick) {
    this.tick = tick;
  }

  public String getPayloadChecksum() {
    return payloadChecksum;
  }

  public void setPayloadChecksum(String payloadChecksum) {
    this.payloadChecksum = payloadChecksum;
  }

  public String getSchemaName() {
    return schemaName;
  }

  public void setSchemaName(String schemaName) {
    this.schemaName = schemaName;
  }

  public LocalDateTime getPersistedAt() {
    return persistedAt;
  }

  public void setPersistedAt(LocalDateTime persistedAt) {
    this.persistedAt = persistedAt;
  }
}
