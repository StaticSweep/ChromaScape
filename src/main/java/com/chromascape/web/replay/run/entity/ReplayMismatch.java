package com.chromascape.web.replay.run.entity;

import com.chromascape.web.replay.api.dto.MismatchLevel;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "replay_mismatches")
public class ReplayMismatch {

  @Id
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "run_id", columnDefinition = "uuid", nullable = false)
  private UUID runId;

  @Column(name = "attempt_id", columnDefinition = "uuid", nullable = false)
  private UUID attemptId;

  @Column(name = "level", nullable = false, length = 10)
  @Enumerated(EnumType.STRING)
  private MismatchLevel level;

  @Column(name = "rule_id", nullable = false)
  private String ruleId;

  @Column(name = "first_tick")
  private Long firstTick;

  @Column(name = "sequence")
  private Integer tickSequence;

  @Column(name = "domain")
  private String domain;

  @Column(name = "json_pointer")
  private String jsonPointer;

  @Column(name = "expected_value", columnDefinition = "text")
  private String expectedValue;

  @Column(name = "actual_value", columnDefinition = "text")
  private String actualValue;

  @Column(name = "related_action_ordinal")
  private Integer relatedActionOrdinal;

  @Column(name = "checkpoint_id", columnDefinition = "uuid")
  private UUID checkpointId;

  @Column(name = "causal_parent_id", columnDefinition = "uuid")
  private UUID causalParentId;

  @Column(name = "cascade_count")
  private Integer cascadeCount;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ReplayMismatch() {}

  private ReplayMismatch(Builder b) {
    this.id = b.id;
    this.runId = b.runId;
    this.attemptId = b.attemptId;
    this.level = b.level;
    this.ruleId = b.ruleId;
    this.firstTick = b.firstTick;
    this.tickSequence = b.tickSequence;
    this.domain = b.domain;
    this.jsonPointer = b.jsonPointer;
    this.expectedValue = b.expectedValue;
    this.actualValue = b.actualValue;
    this.relatedActionOrdinal = b.relatedActionOrdinal;
    this.checkpointId = b.checkpointId;
    this.causalParentId = b.causalParentId;
    this.cascadeCount = b.cascadeCount;
    // createdAt is NEVER self-stamped: the persistence boundary applies ONE PostgreSQL
    // timestamp to every mismatch inserted by an action. First-mismatch ordering depends on
    // this column, so a JVM clock would poison durable ordering under clock skew.
    this.createdAt = null;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRunId() {
    return runId;
  }

  public UUID getAttemptId() {
    return attemptId;
  }

  public MismatchLevel getLevel() {
    return level;
  }

  public String getRuleId() {
    return ruleId;
  }

  public Long getFirstTick() {
    return firstTick;
  }

  public Integer getTickSequence() {
    return tickSequence;
  }

  public String getDomain() {
    return domain;
  }

  public String getJsonPointer() {
    return jsonPointer;
  }

  public String getExpectedValue() {
    return expectedValue;
  }

  public String getActualValue() {
    return actualValue;
  }

  public Integer getRelatedActionOrdinal() {
    return relatedActionOrdinal;
  }

  public UUID getCheckpointId() {
    return checkpointId;
  }

  public UUID getCausalParentId() {
    return causalParentId;
  }

  public Integer getCascadeCount() {
    return cascadeCount;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Persistence-boundary stamping only: ONE PostgreSQL timestamp per durable action. */
  public void stampCreatedAt(Instant createdAt) {
    if (this.createdAt != null) {
      throw new IllegalStateException("mismatch createdAt is already stamped");
    }
    this.createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
  }

  public static class Builder {
    private UUID id = UUID.randomUUID();
    private UUID runId, attemptId;
    private MismatchLevel level;
    private String ruleId;
    private Long firstTick;
    private Integer tickSequence;
    private String domain, jsonPointer, expectedValue, actualValue;
    private Integer relatedActionOrdinal;
    private UUID checkpointId, causalParentId;
    private Integer cascadeCount;

    public Builder(UUID runId, UUID attemptId, MismatchLevel level, String ruleId) {
      this.runId = runId;
      this.attemptId = attemptId;
      this.level = level;
      this.ruleId = ruleId;
    }

    public Builder firstTick(long v) {
      this.firstTick = v;
      return this;
    }

    public Builder tickSequence(int v) {
      this.tickSequence = v;
      return this;
    }

    public Builder domain(String v) {
      this.domain = v;
      return this;
    }

    public Builder jsonPointer(String v) {
      this.jsonPointer = v;
      return this;
    }

    public Builder expectedValue(String v) {
      this.expectedValue = v;
      return this;
    }

    public Builder actualValue(String v) {
      this.actualValue = v;
      return this;
    }

    public Builder relatedActionOrdinal(int v) {
      this.relatedActionOrdinal = v;
      return this;
    }

    public Builder checkpointId(UUID v) {
      this.checkpointId = v;
      return this;
    }

    public Builder causalParentId(UUID v) {
      this.causalParentId = v;
      return this;
    }

    public Builder cascadeCount(int v) {
      this.cascadeCount = v;
      return this;
    }

    public ReplayMismatch build() {
      return new ReplayMismatch(this);
    }
  }
}
