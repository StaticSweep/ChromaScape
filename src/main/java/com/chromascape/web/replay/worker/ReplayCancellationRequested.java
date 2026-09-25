package com.chromascape.web.replay.worker;

/**
 * Shared cancellation signal thrown by the transactional persistence boundary when a locked run is
 * {@code CANCELLING} and the caller attempted an execution-evidence write that requires {@code
 * RUNNING}. Unlike the worker-local read-then-save check this observes the run row under the same
 * lock as the write, so cancellation can no longer slip between the worker's check and its
 * persistence call.
 */
public final class ReplayCancellationRequested extends RuntimeException {

  public ReplayCancellationRequested() {
    super("Cancellation was requested for this run before the finalization cutoff");
  }
}
