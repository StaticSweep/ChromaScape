package com.chromascape.web.aiplayer.routine.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands one {@code sc2106-host-job/v1} to the Micro6 candidate host and returns its single {@code
 * sc2106-host-outcome/v1} (SC-2106 R9.1). The host verifies and supervises the run; this side only
 * carries the job, the cancellation signal and the outcome.
 */
public interface CandidateHostLauncher {

  /**
   * Runs the job to an outcome. Never relaunches. When the host is lost after the job was handed
   * over, the outcome is {@link HostOutcome#lost} because the run may already have had effects.
   */
  HostOutcome run(Map<String, Object> job, Duration timeout, AtomicBoolean cancel);

  /**
   * What came back from the host.
   *
   * @param document the parsed outcome line, or null when none arrived
   * @param lost true when the host went away or timed out after receiving the job
   * @param detail why the outcome is missing, for the job record
   * @param exitCode the host process exit code, or null when it had to be killed
   * @param stderrTail the bounded end of the host's stderr
   */
  record HostOutcome(
      Map<String, Object> document,
      boolean lost,
      String detail,
      Integer exitCode,
      String stderrTail) {}
}
