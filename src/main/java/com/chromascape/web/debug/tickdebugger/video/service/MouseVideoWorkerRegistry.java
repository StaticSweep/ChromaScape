package com.chromascape.web.debug.tickdebugger.video.service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Tracks owned worker executions by job/generation so a cancellation can signal the exact worker
 * that owns publication rights and confirm cleanup before the job is reported terminal. A worker
 * for a stale generation is never signalled.
 */
@Component
public class MouseVideoWorkerRegistry {

  private record Lease(long generation, MouseVideoCancellation cancellation, CountDownLatch done) {}

  private final Map<UUID, Lease> leases = new ConcurrentHashMap<>();

  public MouseVideoCancellation register(UUID jobId, long generation) {
    MouseVideoCancellation cancellation = new MouseVideoCancellation();
    Lease previous = leases.put(jobId, new Lease(generation, cancellation, new CountDownLatch(1)));
    if (previous != null) {
      // A replacement generation must not leave the superseded execution signalling or waiting on
      // the new lease.
      previous.cancellation().requestCancel();
      previous.done().countDown();
    }
    return cancellation;
  }

  /**
   * Deregisters only the exact execution. A delayed callback from an older generation therefore
   * cannot remove or complete a replacement execution's lease.
   */
  public void complete(UUID jobId, long generation) {
    Lease lease = leases.get(jobId);
    if (lease == null || lease.generation() != generation) {
      return;
    }
    if (leases.remove(jobId, lease)) {
      lease.done().countDown();
    }
  }

  public boolean isOwned(UUID jobId) {
    return leases.containsKey(jobId);
  }

  /**
   * Signals the worker that currently owns the job. Cancellation bumps the database generation
   * before this call, so matching the cancelled generation would never match the worker that
   * registered under the previous claim generation. Publication safety is enforced by the fenced
   * database writes, not by this signal.
   */
  public boolean requestCancel(UUID jobId) {
    Lease lease = leases.get(jobId);
    if (lease == null) {
      return false;
    }
    lease.cancellation().requestCancel();
    return true;
  }

  /**
   * Waits for the owned worker to finish cleanup. A missing lease means the worker already
   * deregistered, which is confirmed cleanup. Returns false only when the wait times out or is
   * interrupted.
   */
  public boolean awaitCleanup(UUID jobId, long timeoutMillis) {
    Lease lease = leases.get(jobId);
    if (lease == null) {
      return true;
    }
    try {
      return lease.done().await(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public int ownedCount() {
    return leases.size();
  }

  @PreDestroy
  public void shutdownAll() {
    leases.values().forEach(lease -> lease.cancellation().requestCancel());
  }
}
