package com.chromascape.web.aiplayer.routine.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands one {@code sc2106-host-job/v2} to the Micro6 candidate host and returns its single {@code
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
   * As above, with live pause control (SC-2106 R9.2): while {@code control.pause} is set the run
   * parks before its next step, and {@code control.listener} hears when it actually parked or
   * resumed. A launcher without pause support ignores both.
   */
  default HostOutcome run(Map<String, Object> job, Duration timeout, HostControl control) {
    return run(job, timeout, control.cancel());
  }

  /** Hears the host report that the run parked ({@code true}) or resumed ({@code false}). */
  interface PauseListener {
    void onPause(boolean paused);
  }

  /**
   * Decides a runner's worker binding (SC-2106 R4.2): true once the binding is durably accepted,
   * false when it was refused. The host's runner performs no effect until it has the answer.
   */
  interface BindingHandler {
    boolean accept(Map<String, Object> binding);
  }

  /**
   * The live controls of one run; {@code listener} and {@code binding} may be null. Without a
   * binding handler every binding the host reports is refused.
   */
  record HostControl(
      AtomicBoolean cancel, AtomicBoolean pause, PauseListener listener, BindingHandler binding) {
    public HostControl(AtomicBoolean cancel, AtomicBoolean pause, PauseListener listener) {
      this(cancel, pause, listener, null);
    }
  }

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
