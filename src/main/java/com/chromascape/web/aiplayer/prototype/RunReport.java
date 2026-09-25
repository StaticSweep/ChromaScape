package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Persisted replay-gated run report for the fishing prototype.
 *
 * <p>When the run passes, {@code failureCode}, {@code failureTick}, {@code expected}, and {@code
 * predicted} are omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunReport(
    @JsonProperty("status") String status,
    @JsonProperty("runId") String runId,
    @JsonProperty("modelId") String modelId,
    @JsonProperty("trainingRecordingId") String trainingRecordingId,
    @JsonProperty("testRecordingId") String testRecordingId,
    @JsonProperty("decisionTimingMode") String decisionTimingMode,
    @JsonProperty("expectedDecisionCount") int expectedDecisionCount,
    @JsonProperty("matchedDecisionCount") int matchedDecisionCount,
    @JsonProperty("mismatchCount") int mismatchCount,
    @JsonProperty("abstentionCount") int abstentionCount,
    @JsonProperty("observedCatchDelta") int observedCatchDelta,
    @JsonProperty("fishingXpIncreased") boolean fishingXpIncreased,
    @JsonProperty("decisions") List<RunDecision> decisions,
    @JsonProperty("failureCode") String failureCode,
    @JsonProperty("failureTick") Long failureTick,
    @JsonProperty("expected") SemanticAction expected,
    @JsonProperty("predicted") SemanticAction predicted,
    @JsonProperty("issues") List<PreflightReport.PreflightIssue> issues,
    @JsonProperty("reportPath") String reportPath) {

  /** One matched replay decision at a recorded gate. */
  public record RunDecision(
      @JsonProperty("tick") long tick,
      @JsonProperty("expected") SemanticAction expected,
      @JsonProperty("predicted") SemanticAction predicted,
      @JsonProperty("matched") boolean matched,
      @JsonProperty("targetNpc") FishingState.NearbyNpc targetNpc) {}
}
