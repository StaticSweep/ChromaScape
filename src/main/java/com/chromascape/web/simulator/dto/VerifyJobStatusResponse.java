package com.chromascape.web.simulator.dto;

import com.chromascape.web.simulator.SimulatorVerifyJobStore.JobStatus;
import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifyArgs;
import com.chromascape.web.simulator.SimulatorVerifyJobStore.VerifySummary;
import java.time.Instant;

/**
 * Response containing the current status of a verification job.
 *
 * <p>Includes job metadata, execution status, and results if available.
 */
public class VerifyJobStatusResponse {

  /** Unique identifier for this job. */
  private String jobId;

  /** When the job was requested. */
  private Instant requestedAt;

  /** Current status of the job. */
  private JobStatus status;

  /** Arguments used to start the job. */
  private VerifyArgs args;

  /** Exit code from the verifier process (null until job completes). */
  private Integer exitCode;

  /** Path to the generated report (null until job completes). */
  private String reportPath;

  /** URL to download the full report (null until job completes). */
  private String reportUrl;

  /** Summary of results (null until job completes). */
  private VerifySummary summary;

  /** Error message if the job failed. */
  private String error;

  public VerifyJobStatusResponse() {}

  public String getJobId() {
    return jobId;
  }

  public void setJobId(String jobId) {
    this.jobId = jobId;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public void setRequestedAt(Instant requestedAt) {
    this.requestedAt = requestedAt;
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

  public void setArgs(VerifyArgs args) {
    this.args = args;
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

  public String getReportUrl() {
    return reportUrl;
  }

  public void setReportUrl(String reportUrl) {
    this.reportUrl = reportUrl;
  }

  public VerifySummary getSummary() {
    return summary;
  }

  public void setSummary(VerifySummary summary) {
    this.summary = summary;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }
}
