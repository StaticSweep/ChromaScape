package com.chromascape.web.simulator;

import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory store for simulator verification jobs.
 *
 * <p>Stores job state including status, exit codes, report paths, and results. Uses
 * ConcurrentHashMap for thread-safe access without explicit synchronization.
 */
@Component
public class SimulatorVerifyJobStore {

  /** Job states in the verification lifecycle. */
  public enum JobStatus {
    /** Job is queued and waiting to start. */
    QUEUED,
    /** Job is currently running the verifier. */
    RUNNING,
    /** Job completed successfully (exit code 0 or 1). */
    SUCCEEDED,
    /** Job failed with a runtime error (exit code 2 or other). */
    FAILED
  }

  /** Represents a single verification job. */
  public static class VerifyJob {
    private final String jobId;
    private final Instant requestedAt;
    private JobStatus status;
    private final VerifyArgs args;
    private Integer exitCode;
    private String reportPath;
    private String error;
    private VerifySummary summary;
    private boolean cancelled;

    public VerifyJob(String jobId, VerifyArgs args) {
      this.jobId = jobId;
      this.requestedAt = Instant.now();
      this.status = JobStatus.QUEUED;
      this.args = args;
      this.cancelled = false;
    }

    // Getters and Setters

    public String getJobId() {
      return jobId;
    }

    public Instant getRequestedAt() {
      return requestedAt;
    }

    public JobStatus getStatus() {
      return status;
    }

    public void setStatus(JobStatus status) {
      this.status = status;
    }

    public VerifyArgs getArgs() {
      return args;
    }

    public Integer getExitCode() {
      return exitCode;
    }

    public void setExitCode(Integer exitCode) {
      this.exitCode = exitCode;
    }

    public String getReportPath() {
      return reportPath;
    }

    public void setReportPath(String reportPath) {
      this.reportPath = reportPath;
    }

    public String getError() {
      return error;
    }

    public void setError(String error) {
      this.error = error;
    }

    public VerifySummary getSummary() {
      return summary;
    }

    public void setSummary(VerifySummary summary) {
      this.summary = summary;
    }

    public boolean isCancelled() {
      return cancelled;
    }

    public void setCancelled(boolean cancelled) {
      this.cancelled = cancelled;
    }
  }

  /** Arguments passed to start a verification job. */
  public static class VerifyArgs {
    private final String session;
    private final String scenario;
    private final String bridgeUrl;
    private final String apiKey;

    public VerifyArgs(String session, String scenario, String bridgeUrl, String apiKey) {
      this.session = session;
      this.scenario = scenario;
      this.bridgeUrl = bridgeUrl;
      this.apiKey = apiKey;
    }

    public String getSession() {
      return session;
    }

    public String getScenario() {
      return scenario;
    }

    public String getBridgeUrl() {
      return bridgeUrl;
    }

    public String getApiKey() {
      return apiKey;
    }
  }

  /** Summary of verification results from the report. */
  public static class VerifySummary {
    private boolean passed;
    private int totalActions;
    private int startTick;
    private int endTick;
    private int playerCount;
    private long durationMs;
    private ViolationCounts violations;

    public static class ViolationCounts {
      private int hard;
      private int soft;
      private int warn;
      private int notEvaluated;

      public ViolationCounts(int hard, int soft, int warn, int notEvaluated) {
        this.hard = hard;
        this.soft = soft;
        this.warn = warn;
        this.notEvaluated = notEvaluated;
      }

      public int getHard() {
        return hard;
      }

      public int getSoft() {
        return soft;
      }

      public int getWarn() {
        return warn;
      }

      public int getNotEvaluated() {
        return notEvaluated;
      }
    }

    public VerifySummary(
        boolean passed,
        int totalActions,
        int startTick,
        int endTick,
        int playerCount,
        long durationMs,
        ViolationCounts violations) {
      this.passed = passed;
      this.totalActions = totalActions;
      this.startTick = startTick;
      this.endTick = endTick;
      this.playerCount = playerCount;
      this.durationMs = durationMs;
      this.violations = violations;
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

    public int getStartTick() {
      return startTick;
    }

    public int getEndTick() {
      return endTick;
    }

    public int getPlayerCount() {
      return playerCount;
    }

    public long getDurationMs() {
      return durationMs;
    }

    public ViolationCounts getViolations() {
      return violations;
    }
  }

  private final ConcurrentHashMap<String, VerifyJob> jobs = new ConcurrentHashMap<>();

  /**
   * Creates a new job with the given ID and arguments.
   *
   * @param jobId Unique identifier for the job
   * @param args Verification arguments
   * @return The created job
   */
  public VerifyJob createJob(String jobId, VerifyArgs args) {
    VerifyJob job = new VerifyJob(jobId, args);
    jobs.put(jobId, job);
    return job;
  }

  /**
   * Retrieves a job by ID.
   *
   * @param jobId Job identifier
   * @return The job, or null if not found
   */
  public VerifyJob getJob(String jobId) {
    return jobs.get(jobId);
  }

  /**
   * Returns all stored jobs.
   *
   * @return Collection of all jobs
   */
  public Collection<VerifyJob> getAllJobs() {
    return jobs.values();
  }

  /**
   * Removes a job from storage.
   *
   * @param jobId Job identifier
   * @return The removed job, or null if not found
   */
  public VerifyJob removeJob(String jobId) {
    return jobs.remove(jobId);
  }
}
