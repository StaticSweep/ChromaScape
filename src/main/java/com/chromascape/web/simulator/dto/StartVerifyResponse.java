package com.chromascape.web.simulator.dto;

import com.chromascape.web.simulator.SimulatorVerifyJobStore.JobStatus;

/**
 * Response when starting a new verification job.
 *
 * <p>Provides the job ID for polling status and initial status.
 */
public class StartVerifyResponse {

  /** Unique identifier for this verification job. */
  private String jobId;

  /** Current status of the job (should be QUEUED). */
  private JobStatus status;

  /** URL to poll for job status updates. */
  private String statusUrl;

  /** Estimated time to completion in seconds (optional). */
  private Integer estimatedSeconds;

  public StartVerifyResponse() {}

  public StartVerifyResponse(String jobId, JobStatus status) {
    this.jobId = jobId;
    this.status = status;
  }

  public String getJobId() {
    return jobId;
  }

  public void setJobId(String jobId) {
    this.jobId = jobId;
  }

  public JobStatus getStatus() {
    return status;
  }

  public void setStatus(JobStatus status) {
    this.status = status;
  }

  public String getStatusUrl() {
    return statusUrl;
  }

  public void setStatusUrl(String statusUrl) {
    this.statusUrl = statusUrl;
  }

  public Integer getEstimatedSeconds() {
    return estimatedSeconds;
  }

  public void setEstimatedSeconds(Integer estimatedSeconds) {
    this.estimatedSeconds = estimatedSeconds;
  }
}
