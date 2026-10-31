package com.chromascape.web.debug.tickdebugger.video.service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation token for one owned job generation. The owning thread binds itself so a
 * cancel request can interrupt it; the worker checks {@link #isCancelled()} at bounded intervals
 * and terminates only its own process group/descendants during cleanup.
 */
public final class MouseVideoCancellation {

  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private volatile Thread owner;

  public void bindThread(Thread thread) {
    this.owner = thread;
    if (cancelled.get() && thread != null) {
      thread.interrupt();
    }
  }

  public boolean isCancelled() {
    return cancelled.get();
  }

  public void requestCancel() {
    cancelled.set(true);
    Thread thread = owner;
    if (thread != null) {
      thread.interrupt();
    }
  }

  /**
   * Cancels because this server is shutting down, not because a user asked. The worker still
   * terminates its own processes; with cleanup confirmed it re-queues the job instead of reporting
   * a cancellation nobody requested.
   */
  public void requestShutdown() {
    shutdown.set(true);
    requestCancel();
  }

  public boolean isShutdownRequested() {
    return shutdown.get();
  }
}
