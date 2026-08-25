package com.chromascape.web.replay.run.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "replay_events")
@IdClass(ReplayEventId.class)
public class ReplayEvent {

    @Id
    @Column(name = "run_id")
    private UUID runId;

    @Id
    @Column(nullable = false)
    private long ordinal;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(nullable = false)
    private long tick;

    @Column(nullable = false)
    private int sequence;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private JsonNode payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ReplayEvent() {}

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public long getOrdinal() { return ordinal; }
    public void setOrdinal(long ordinal) { this.ordinal = ordinal; }
    public UUID getAttemptId() { return attemptId; }
    public void setAttemptId(UUID attemptId) { this.attemptId = attemptId; }
    public long getTick() { return tick; }
    public void setTick(long tick) { this.tick = tick; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}