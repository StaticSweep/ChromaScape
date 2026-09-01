package com.chromascape.web.replay.kernel;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, escalation-based termination of a child process AND its full descendant tree. Used by
 * both kernel workers and capability validation so no orphaned Node children survive any teardown
 * path.
 *
 * <p>ONE absolute deadline governs the ENTIRE tree teardown: graceful signals, descendant waits,
 * parent natural-exit wait, and escalation all consume the SAME budget. When the budget is
 * exhausted, everything still alive is destroyed forcibly WITHOUT another grace window; only a
 * small fixed final reap allowance is granted for collecting the forcibly-signalled processes.
 * Total teardown time is therefore bounded by {@code shutdownTimeoutMs + FINAL_REAP_ALLOWANCE_MS}
 * regardless of how many descendants exist — never a fresh timeout per target.
 */
public final class XrspsProcessTreeTerminator {

  /**
   * The documented, small fixed allowance for reaping processes AFTER the graceful budget is
   * exhausted (forcible kill → OS collection). Never extended, never multiplied by the number of
   * targets.
   */
  public static final long FINAL_REAP_ALLOWANCE_MS = 2000;

  private static final long POLL_SLICE_MS = 25;

  private XrspsProcessTreeTerminator() {}

  /**
   * Standalone entry point: creates ONE absolute deadline from {@code shutdownTimeoutMs} and
   * delegates to {@link #terminate(Process, long)}.
   *
   * @param shutdownTimeoutMs total graceful window granted to the WHOLE tree before forcible
   *     destruction
   */
  public static void terminate(Process process, long shutdownTimeoutMs) {
    terminateWithinDeadline(
        process, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownTimeoutMs));
  }

  /**
   * Shared-deadline termination: every stage below consumes the SAME absolute {@code deadlineNanos}
   * budget. Package-private so {@link XrspsKernelProcess#close()} can hand its already-running
   * deadline in.
   *
   * <ol>
   *   <li>graceful destroy() over every descendant and the parent (signals are free);
   *   <li>bounded exit waits that each consume only the REMAINING shared budget — a slow first
   *       descendant can never grant later ones fresh windows;
   *   <li>once the budget is exhausted: destroyForcibly() on EVERYTHING still alive with NO further
   *       grace;
   *   <li>a single small fixed reap allowance to collect the forced kills.
   * </ol>
   */
  static void terminateWithinDeadline(Process process, long deadlineNanos) {
    if (process == null) {
      return;
    }
    List<ProcessHandle> descendants = process.descendants().toList();

    // 1. Graceful destruction across the whole tree first: signals cost nothing.
    for (ProcessHandle handle : descendants) {
      handle.destroy();
    }
    if (process.isAlive()) {
      process.destroy();
    }

    // 2. ONE shared graceful window: every wait consumes the SAME absolute budget.
    for (ProcessHandle handle : descendants) {
      awaitExit(handle, deadlineNanos);
    }
    if (process.isAlive()) {
      awaitExit(process.toHandle(), deadlineNanos);
    }

    // 3. Budget exhausted: forcibly terminate whatever remains WITHOUT another grace window.
    for (ProcessHandle handle : descendants) {
      if (handle.isAlive()) {
        handle.destroyForcibly();
      }
    }
    if (process.isAlive()) {
      process.destroyForcibly();
    }

    // 4. Small fixed final reap allowance — the ONLY time granted beyond the deadline.
    long reapDeadlineNanos =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FINAL_REAP_ALLOWANCE_MS);
    for (ProcessHandle handle : descendants) {
      awaitExit(handle, reapDeadlineNanos);
    }
    if (process.isAlive()) {
      awaitExit(process.toHandle(), reapDeadlineNanos);
    }
  }

  /** Polls until the handle exits or the ABSOLUTE deadline passes. Never grants extra time. */
  private static void awaitExit(ProcessHandle handle, long deadlineNanos) {
    try {
      while (handle.isAlive()) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
          return;
        }
        long sliceMs = Math.min(POLL_SLICE_MS, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        Thread.sleep(Math.max(1, sliceMs));
      }
    } catch (InterruptedException e) {
      handle.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
}
