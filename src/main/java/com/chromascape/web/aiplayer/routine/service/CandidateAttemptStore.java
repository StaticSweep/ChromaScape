package com.chromascape.web.aiplayer.routine.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Durable ownership of candidate execution attempts (SC-2106 R4).
 *
 * <p>An (owner, idempotency key) pair maps to exactly one attempt, reserved before anything is
 * launched. Every later write presents the lease and epoch it holds; a write by any other holder is
 * refused with {@link StaleOwnerException}. Reconciliation of an expired lease moves the attempt to
 * {@link State#UNCERTAIN} and bumps the epoch, so the old owner is fenced even if it later reuses
 * the same lease id. An uncertain attempt is never relaunched automatically.
 */
public interface CandidateAttemptStore {

  enum State {
    RESERVED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNCERTAIN;

    public boolean terminal() {
      return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean open() {
      return this == RESERVED || this == RUNNING;
    }
  }

  record Attempt(
      String attemptId,
      String ownerId,
      String idempotencyKey,
      String jobId,
      String fingerprint,
      State state,
      String leaseId,
      long epoch,
      Instant leaseExpiresAt,
      String requestDigest,
      Map<String, Object> job,
      String detail) {}

  /** {@code created} is false when the key already had an attempt; nothing new was reserved. */
  record Reservation(Attempt attempt, boolean created) {}

  /** A write by a holder whose lease or epoch is no longer current. */
  final class StaleOwnerException extends RuntimeException {
    public StaleOwnerException(String message) {
      super(message);
    }
  }

  /** Another branch of the recovery lineage is live, finished or uncertain (SC-2106 R6). */
  final class RecoveryClaimedException extends RuntimeException {
    private final String holder;

    public RecoveryClaimedException(String recovers, String holder) {
      super("attempt " + recovers + " is already recovered by " + holder);
      this.holder = holder;
    }

    public String holder() {
      return holder;
    }
  }

  Reservation reserve(
      String ownerId, String idempotencyKey, String jobId, String fingerprint, Duration lease);

  /**
   * As {@link #reserve}, for a recovery of {@code recovers}. Reservations are serialized across the
   * original attempt's entire recovery lineage. RESERVED, RUNNING, COMPLETED or UNCERTAIN attempts
   * on another branch block a new continuation with {@link RecoveryClaimedException}. Uncertain
   * ancestors allow their own continuation; a retry under an existing key only reattaches.
   */
  Reservation reserveRecovery(
      String ownerId,
      String idempotencyKey,
      String jobId,
      String fingerprint,
      Duration lease,
      String recovers);

  /** RESERVED to RUNNING, recording the request digest the launch will carry. */
  Attempt markRunning(String attemptId, String leaseId, long epoch, String requestDigest);

  /** Extends an open attempt's lease; false when the holder has been fenced. */
  boolean renew(String attemptId, String leaseId, long epoch, Duration lease);

  /** Records the terminal (or uncertain) result; the lease is released. */
  Attempt finish(
      String attemptId,
      String leaseId,
      long epoch,
      State state,
      Map<String, Object> job,
      String detail);

  /** Open attempts whose lease expired before {@code now} become UNCERTAIN; returns how many. */
  int reconcileExpired(Instant now);

  Optional<Attempt> find(String attemptId);
}
