package com.chromascape.web.simulator.dto;

import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifySummary;
import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifySummary.ViolationCounts;

/**
 * Detailed verification summary response aligned with the simulator-verifier output format.
 *
 * <p>Provides comprehensive results including pass/fail status, action counts, tick ranges,
 * duration, player information, and violation breakdown by severity.
 */
public class VerifySummaryResponse {

  /** Whether the verification passed (no hard violations). */
  private boolean passed;

  /** Total number of actions processed during verification. */
  private int totalActions;

  /** Starting tick number of the verified session. */
  private int startTick;

  /** Ending tick number of the verified session. */
  private int endTick;

  /** Number of players in the verified session. */
  private int playerCount;

  /** Duration of the verification in milliseconds. */
  private long durationMs;

  /** Human-readable duration string (e.g., "2m 34s"). */
  private String durationFormatted;

  /** Breakdown of violations by severity level. */
  private ViolationCountsDto violations;

  /** Detailed DTO for violation counts. */
  public static class ViolationCountsDto {
    private int hard;
    private int soft;
    private int warn;
    private int notEvaluated;

    public ViolationCountsDto() {}

    public ViolationCountsDto(ViolationCounts counts) {
      this.hard = counts.getHard();
      this.soft = counts.getSoft();
      this.warn = counts.getWarn();
      this.notEvaluated = counts.getNotEvaluated();
    }

    public int getHard() {
      return hard;
    }

    public void setHard(int hard) {
      this.hard = hard;
    }

    public int getSoft() {
      return soft;
    }

    public void setSoft(int soft) {
      this.soft = soft;
    }

    public int getWarn() {
      return warn;
    }

    public void setWarn(int warn) {
      this.warn = warn;
    }

    public int getNotEvaluated() {
      return notEvaluated;
    }

    public void setNotEvaluated(int notEvaluated) {
      this.notEvaluated = notEvaluated;
    }
  }

  public VerifySummaryResponse() {}

  public VerifySummaryResponse(VerifySummary summary) {
    this.passed = summary.isPassed();
    this.totalActions = summary.getTotalActions();
    this.startTick = summary.getStartTick();
    this.endTick = summary.getEndTick();
    this.playerCount = summary.getPlayerCount();
    this.durationMs = summary.getDurationMs();
    this.violations = new ViolationCountsDto(summary.getViolations());
    this.durationFormatted = formatDuration(summary.getDurationMs());
  }

  private String formatDuration(long ms) {
    long seconds = ms / 1000;
    long minutes = seconds / 60;
    seconds = seconds % 60;
    if (minutes > 0) {
      return minutes + "m " + seconds + "s";
    }
    return seconds + "s";
  }

  public boolean isPassed() {
    return passed;
  }

  public void setPassed(boolean passed) {
    this.passed = passed;
  }

  public int getTotalActions() {
    return totalActions;
  }

  public void setTotalActions(int totalActions) {
    this.totalActions = totalActions;
  }

  public int getStartTick() {
    return startTick;
  }

  public void setStartTick(int startTick) {
    this.startTick = startTick;
  }

  public int getEndTick() {
    return endTick;
  }

  public void setEndTick(int endTick) {
    this.endTick = endTick;
  }

  public int getPlayerCount() {
    return playerCount;
  }

  public void setPlayerCount(int playerCount) {
    this.playerCount = playerCount;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(long durationMs) {
    this.durationMs = durationMs;
  }

  public String getDurationFormatted() {
    return durationFormatted;
  }

  public void setDurationFormatted(String durationFormatted) {
    this.durationFormatted = durationFormatted;
  }

  public ViolationCountsDto getViolations() {
    return violations;
  }

  public void setViolations(ViolationCountsDto violations) {
    this.violations = violations;
  }
}
