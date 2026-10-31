package com.chromascape.web.debug.tickdebugger.video.service;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.MouseVideoProperties;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJob;
import com.chromascape.web.debug.tickdebugger.video.entity.MouseVideoJobRepository;
import java.util.List;

/**
 * Database-backed owner admission authority shared by import registration and analysis/preview
 * scheduling.
 *
 * <p>{@link #lock} must be the first statement of any transaction that may insert a job. It takes a
 * transaction-scoped PostgreSQL advisory lock keyed by owner, so count-then-insert is atomic across
 * threads and instances and is released at commit or rollback. Lock order is always this advisory
 * lock, then job/preview/analysis rows, then the owner budget row; no path acquires them in another
 * order. Reconciliation-required jobs keep occupying their slot until resolved.
 */
public final class MouseVideoAdmission {

  /** Statuses that occupy an admission slot. */
  public static final List<String> OCCUPYING_STATUSES =
      List.of(
          MouseVideoJob.STATUS_QUEUED,
          MouseVideoJob.STATUS_RUNNING,
          MouseVideoJob.STATUS_CANCELLING,
          MouseVideoJob.STATUS_NEEDS_RECONCILIATION);

  private final MouseVideoJobRepository jobs;
  private final MouseVideoProperties properties;

  /** Creates an admission authority over the shared job store and configured waiting slots. */
  public MouseVideoAdmission(MouseVideoJobRepository jobs, MouseVideoProperties properties) {
    this.jobs = jobs;
    this.properties = properties;
  }

  /** Serializes admission for this owner until the surrounding transaction ends. */
  public void lock(String owner) {
    jobs.lockAdmission("mouse-video-admission:" + owner);
  }

  /** One running job plus the configured waiting slots. */
  public long limit() {
    return Math.max(1, properties.getWaitingSlots()) + 1L;
  }

  /** Rejects a new job when the owner is at the limit. Callers must already hold {@link #lock}. */
  public void requireCapacity(String owner) {
    if (jobs.countByOwnerAndStatusIn(owner, OCCUPYING_STATUSES) >= limit()) {
      throw new MouseVideoException(
          MouseVideoException.CODE_QUOTA_EXCEEDED,
          "The owner has reached the active/waiting job admission limit");
    }
  }
}
