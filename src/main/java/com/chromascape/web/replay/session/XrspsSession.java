package com.chromascape.web.replay.session;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * One ChromaScape-owned xRSPS scenario session: one worker, one mutable scenario world, one
 * execution mode. The real client attaches as an observer of this world's production
 * synchronization; it never creates a second player or world.
 *
 * <p>All state changes go through synchronized transitions so a session owned by one worker cannot
 * be mutated concurrently by a stale holder. Command journaling and lease fencing live in {@link
 * XrspsSessionStore}.
 */
public class XrspsSession {

  /** Maximum ticks a single explicit step request may advance while paused. */
  public static final int MAX_STEP_TICKS = 1000;

  private final UUID id;
  private final XrspsSessionMode mode;
  private final String idempotencyKey;
  private final String scenarioRef;
  private XrspsSessionStatus status;
  private long tick;
  private long observationRevision;
  private long epoch;
  private String leaseId;
  private String terminalReason;
  private Instant updatedAt;

  public XrspsSession(UUID id, XrspsSessionMode mode, String idempotencyKey, String scenarioRef) {
    this.id = id;
    this.mode = mode;
    this.idempotencyKey = idempotencyKey;
    this.scenarioRef = scenarioRef;
    this.status = XrspsSessionStatus.CREATING;
    this.tick = 0L;
    this.observationRevision = 0L;
    this.epoch = 0L;
    this.leaseId = UUID.randomUUID().toString();
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public XrspsSessionMode getMode() {
    return mode;
  }

  public synchronized XrspsSessionStatus getStatus() {
    return status;
  }

  public synchronized long getTick() {
    return tick;
  }

  public synchronized long getObservationRevision() {
    return observationRevision;
  }

  public synchronized long getEpoch() {
    return epoch;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getScenarioRef() {
    return scenarioRef;
  }

  public synchronized String getLeaseId() {
    return leaseId;
  }

  public synchronized String getTerminalReason() {
    return terminalReason;
  }

  /** CREATING -> READY once scenario validation passes. */
  public synchronized void markReady() {
    transitionTo(XrspsSessionStatus.READY, null);
  }

  /** READY -> RUNNING. Starts execution exactly once after scenario validation. */
  public synchronized void start() {
    transitionTo(XrspsSessionStatus.RUNNING, null);
  }

  /** RUNNING -> PAUSED at a documented safe tick boundary. */
  public synchronized void pause() {
    transitionTo(XrspsSessionStatus.PAUSED, null);
  }

  /** PAUSED -> RUNNING. */
  public synchronized void resume() {
    transitionTo(XrspsSessionStatus.RUNNING, null);
  }

  /**
   * Advances a bounded number of ticks. Only legal while paused; the current action lifecycle is
   * preserved by the caller.
   */
  public synchronized void stepTicks(int ticks) {
    requireStatus(XrspsSessionStatus.PAUSED, "step");
    if (ticks < 1 || ticks > MAX_STEP_TICKS) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.BAD_REQUEST,
          false,
          "Step tick count out of range",
          "Request between 1 and " + MAX_STEP_TICKS + " ticks while the session is paused");
    }
    tick += ticks;
    touch();
  }

  /** Advances ticks while running. Wall-clock pacing only decides when a tick is displayed. */
  public synchronized void advanceTicks(long ticks) {
    requireStatus(XrspsSessionStatus.RUNNING, "advance ticks for");
    if (ticks < 1) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.BAD_REQUEST,
          false,
          "Tick advance must be positive",
          "Advance by at least one tick while the session is running");
    }
    tick += ticks;
    touch();
  }

  /**
   * Applies one accepted command. Only the store calls this after journaling the command, so the
   * observation revision always matches the journal order.
   */
  synchronized void applyAcceptedCommand() {
    requireStatus(XrspsSessionStatus.RUNNING, "accept commands for");
    observationRevision += 1;
    touch();
  }

  /**
   * Applies one stepped command while paused. Only the store calls this from the coordinated
   * single-step path, so due actions execute during the step instead of going late on resume.
   * Advance ticks only through the recorded-action runner while paused; direct {@link #stepTicks}
   * past a pending action leaves it late on the next running pass by design.
   */
  synchronized void applySteppedCommand() {
    requireStatus(XrspsSessionStatus.PAUSED, "accept stepped commands for");
    observationRevision += 1;
    touch();
  }

  /** Fences new commands and stops at the safe boundary from RUNNING or PAUSED. */
  public synchronized void cancel(String reason) {
    transitionTo(XrspsSessionStatus.CANCELLED, reason);
  }

  /** Terminal success from RUNNING or PAUSED. */
  public synchronized void complete(String reason) {
    transitionTo(XrspsSessionStatus.SUCCEEDED, reason);
  }

  /** Terminal failure from RUNNING, PAUSED or RECONCILING. */
  public synchronized void fail(String reason) {
    transitionTo(XrspsSessionStatus.FAILED, reason);
  }

  /**
   * Moves a live session to reconciliation after worker loss. The lease stays attached so only the
   * holder recorded here can be fenced during recovery; no duplicate execution starts here.
   */
  public synchronized void reportWorkerLoss() {
    if (status != XrspsSessionStatus.RUNNING && status != XrspsSessionStatus.PAUSED) {
      throw illegalTransition(status, XrspsSessionStatus.RECONCILING);
    }
    transitionTo(XrspsSessionStatus.RECONCILING, null);
  }

  /**
   * Recovers from a verified checkpoint after the previous lease is fenced. The caller proves
   * fencing by presenting the exact lease that was active when the worker was lost. Recovery bumps
   * the epoch, which invalidates all prior viewer packets, and parks the session paused. Tick,
   * observation revision and the store journal are preserved; a fresh worker resumes by replaying
   * the journal (applied actions return DUPLICATE with no duplicate effects) and old-lease commands
   * are fenced by the store. Full world-snapshot restore arrives with the authoritative world in
   * Phase 2.
   */
  public synchronized void recoverFromCheckpoint(String fencedLeaseId, String newLeaseId) {
    requireStatus(XrspsSessionStatus.RECONCILING, "recover");
    if (fencedLeaseId == null || !fencedLeaseId.equals(leaseId)) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.CONFLICT,
          false,
          "Lease fencing violated",
          "Recover only with the exact lease that was active when the worker was lost");
    }
    leaseId = newLeaseId;
    epoch += 1;
    transitionTo(XrspsSessionStatus.PAUSED, null);
  }

  private void transitionTo(XrspsSessionStatus next, String reason) {
    if (!status.canTransitionTo(next)) {
      throw illegalTransition(status, next);
    }
    status = next;
    if (next.isTerminal()) {
      terminalReason = reason;
    }
    touch();
  }

  private void requireStatus(XrspsSessionStatus required, String action) {
    if (status != required) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.CONFLICT,
          false,
          "Session cannot " + action + " while " + status,
          "Move the session to " + required + " first");
    }
  }

  private static ReplayException illegalTransition(XrspsSessionStatus from, XrspsSessionStatus to) {
    return new ReplayException(
        ReplayErrorCode.VALIDATION_ERROR,
        HttpStatus.CONFLICT,
        false,
        "Illegal session transition from " + from + " to " + to,
        "Follow CREATING -> READY -> RUNNING with explicit pause, stop and terminal states");
  }

  private void touch() {
    updatedAt = Instant.now();
  }
}
