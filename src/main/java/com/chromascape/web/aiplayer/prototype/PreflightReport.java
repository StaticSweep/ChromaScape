package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Preflight report for a fishing recording.
 *
 * <p>Reports {@code READY} when every prototype-one acceptance condition holds, otherwise {@code
 * NOT_READY} with stable issue codes.
 */
public record PreflightReport(
    @JsonProperty("status") String status,
    @JsonProperty("recordingId") String recordingId,
    @JsonProperty("sessionId") String sessionId,
    @JsonProperty("finalized") boolean finalized,
    @JsonProperty("snapshotCount") int snapshotCount,
    @JsonProperty("playerActionCount") int playerActionCount,
    @JsonProperty("fishingActionCount") int fishingActionCount,
    @JsonProperty("alignedFishingActionCount") int alignedFishingActionCount,
    @JsonProperty("unsupportedActionCount") int unsupportedActionCount,
    @JsonProperty("actionQueueOverflowCount") int actionQueueOverflowCount,
    @JsonProperty("observedCatchDelta") int observedCatchDelta,
    @JsonProperty("fishingXpIncreased") boolean fishingXpIncreased,
    @JsonProperty("issues") List<PreflightIssue> issues) {

  /** Stable issue codes for prototype one. */
  public static final class IssueCode {
    public static final String RECORDING_NOT_FOUND = "RECORDING_NOT_FOUND";
    public static final String SESSION_NOT_FINALIZED = "SESSION_NOT_FINALIZED";
    public static final String NO_TICK_SNAPSHOTS = "NO_TICK_SNAPSHOTS";
    public static final String NO_PLAYER_ACTIONS = "NO_PLAYER_ACTIONS";
    public static final String NO_FISHING_ACTIONS = "NO_FISHING_ACTIONS";
    public static final String ACTION_WITHOUT_SNAPSHOT = "ACTION_WITHOUT_SNAPSHOT";
    public static final String NPC_TELEMETRY_MISSING = "NPC_TELEMETRY_MISSING";
    public static final String ACTION_QUEUE_OVERFLOW = "ACTION_QUEUE_OVERFLOW";
    public static final String UNSUPPORTED_ACTION_PRESENT = "UNSUPPORTED_ACTION_PRESENT";
    public static final String CATCH_COUNT_MISMATCH = "CATCH_COUNT_MISMATCH";
    public static final String FISHING_XP_UNCHANGED = "FISHING_XP_UNCHANGED";

    private IssueCode() {}
  }

  /** A single preflight issue with a stable code and human-readable message. */
  public record PreflightIssue(
      @JsonProperty("code") String code, @JsonProperty("message") String message) {}

  public boolean isReady() {
    return "READY".equals(status);
  }

  /** Creates a READY report. */
  public static PreflightReport ready(
      String recordingId,
      String sessionId,
      boolean finalized,
      int snapshotCount,
      int playerActionCount,
      int fishingActionCount,
      int alignedFishingActionCount,
      int unsupportedActionCount,
      int actionQueueOverflowCount,
      int observedCatchDelta,
      boolean fishingXpIncreased) {
    return new PreflightReport(
        "READY",
        recordingId,
        sessionId,
        finalized,
        snapshotCount,
        playerActionCount,
        fishingActionCount,
        alignedFishingActionCount,
        unsupportedActionCount,
        actionQueueOverflowCount,
        observedCatchDelta,
        fishingXpIncreased,
        List.of());
  }

  /** Creates a NOT_READY report. */
  public static PreflightReport notReady(
      String recordingId, String sessionId, List<PreflightIssue> issues) {
    return new PreflightReport(
        "NOT_READY", recordingId, sessionId, false, 0, 0, 0, 0, 0, 0, 0, false, issues);
  }
}
