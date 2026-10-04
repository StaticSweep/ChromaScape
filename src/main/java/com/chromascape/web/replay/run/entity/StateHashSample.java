package com.chromascape.web.replay.run.entity;

import jakarta.persistence.*;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "state_hash_samples")
public class StateHashSample {

  @EmbeddedId private StateHashSampleId id;

  @Column(name = "projection_version", nullable = false)
  private int projectionVersion;

  @Column(name = "state_hash", nullable = false)
  private String stateHash;

  @Column(name = "chain_hash", nullable = false)
  private String chainHash;

  @Column(name = "transition_ordinal")
  private Long transitionOrdinal;

  public Long getTransitionOrdinal() {
    return transitionOrdinal;
  }

  public void setTransitionOrdinal(Long ordinal) {
    this.transitionOrdinal = ordinal;
  }

  protected StateHashSample() {}

  public StateHashSample(
      UUID runId,
      long tick,
      int sequence,
      int projectionVersion,
      String stateHash,
      String chainHash) {
    this.id = new StateHashSampleId(runId, tick, sequence);
    this.projectionVersion = projectionVersion;
    this.stateHash = stateHash;
    this.chainHash = chainHash;
  }

  public StateHashSampleId getId() {
    return id;
  }

  public int getProjectionVersion() {
    return projectionVersion;
  }

  public String getStateHash() {
    return stateHash;
  }

  public String getChainHash() {
    return chainHash;
  }

  @Embeddable
  public static class StateHashSampleId {
    @Column(name = "run_id", columnDefinition = "uuid")
    private UUID runId;

    @Column(name = "tick")
    private long tick;

    @Column(name = "sequence")
    private int sequence;

    protected StateHashSampleId() {}

    public StateHashSampleId(UUID runId, long tick, int sequence) {
      this.runId = runId;
      this.tick = tick;
      this.sequence = sequence;
    }

    public UUID getRunId() {
      return runId;
    }

    public long getTick() {
      return tick;
    }

    public int getSequence() {
      return sequence;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof StateHashSampleId that)) return false;
      return tick == that.tick && sequence == that.sequence && Objects.equals(runId, that.runId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(runId, tick, sequence);
    }
  }
}
