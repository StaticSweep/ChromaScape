package com.chromascape.web.aiplayer.routine.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test double with the same fencing rules as {@link JdbcCandidateAttemptStore}. */
final class InMemoryCandidateAttemptStore implements CandidateAttemptStore {
  final Map<String, Attempt> byId = new LinkedHashMap<>();
  int launchesMarked;

  @Override
  public synchronized Reservation reserve(
      String ownerId, String idempotencyKey, String jobId, String fingerprint, Duration lease) {
    for (Attempt existing : byId.values()) {
      if (existing.ownerId().equals(ownerId) && existing.idempotencyKey().equals(idempotencyKey)) {
        return new Reservation(existing, false);
      }
    }
    Attempt created =
        new Attempt(
            "attempt-" + UUID.randomUUID(),
            ownerId,
            idempotencyKey,
            jobId,
            fingerprint,
            State.RESERVED,
            "lease-" + UUID.randomUUID(),
            1L,
            Instant.now().plus(lease),
            null,
            null,
            null);
    byId.put(created.attemptId(), created);
    return new Reservation(created, true);
  }

  @Override
  public synchronized Attempt markRunning(
      String attemptId, String leaseId, long epoch, String requestDigest) {
    Attempt a = held(attemptId, leaseId, epoch);
    if (a.state() != State.RESERVED) {
      throw new StaleOwnerException(attemptId + " is " + a.state());
    }
    launchesMarked++;
    return put(
        with(
            a,
            State.RUNNING,
            a.leaseId(),
            a.epoch(),
            a.leaseExpiresAt(),
            requestDigest,
            a.job(),
            a.detail()));
  }

  @Override
  public synchronized boolean renew(String attemptId, String leaseId, long epoch, Duration lease) {
    try {
      Attempt a = held(attemptId, leaseId, epoch);
      put(
          with(
              a,
              a.state(),
              a.leaseId(),
              a.epoch(),
              Instant.now().plus(lease),
              a.requestDigest(),
              a.job(),
              a.detail()));
      return true;
    } catch (StaleOwnerException e) {
      return false;
    }
  }

  @Override
  public synchronized Attempt finish(
      String attemptId,
      String leaseId,
      long epoch,
      State state,
      Map<String, Object> job,
      String detail) {
    Attempt a = held(attemptId, leaseId, epoch);
    return put(with(a, state, null, a.epoch(), null, a.requestDigest(), job, detail));
  }

  @Override
  public synchronized int reconcileExpired(Instant now) {
    int count = 0;
    for (Attempt a : byId.values().toArray(new Attempt[0])) {
      if (a.state().open() && a.leaseExpiresAt() != null && a.leaseExpiresAt().isBefore(now)) {
        put(
            with(
                a,
                State.UNCERTAIN,
                null,
                a.epoch() + 1,
                null,
                a.requestDigest(),
                a.job(),
                "owner lease expired"));
        count++;
      }
    }
    return count;
  }

  @Override
  public synchronized Optional<Attempt> find(String attemptId) {
    return Optional.ofNullable(byId.get(attemptId));
  }

  /** Simulates another owner taking the attempt over (reconciliation after a lost lease). */
  synchronized void expireAndReconcile(String attemptId) {
    Attempt a = byId.get(attemptId);
    put(
        with(
            a,
            a.state(),
            a.leaseId(),
            a.epoch(),
            Instant.EPOCH,
            a.requestDigest(),
            a.job(),
            a.detail()));
    reconcileExpired(Instant.now());
  }

  private Attempt held(String attemptId, String leaseId, long epoch) {
    Attempt a = byId.get(attemptId);
    if (a == null || !a.state().open() || !leaseId.equals(a.leaseId()) || a.epoch() != epoch) {
      throw new StaleOwnerException(attemptId + " is not held by " + leaseId + "/" + epoch);
    }
    return a;
  }

  private Attempt put(Attempt a) {
    byId.put(a.attemptId(), a);
    return a;
  }

  private static Attempt with(
      Attempt a,
      State state,
      String leaseId,
      long epoch,
      Instant expires,
      String requestDigest,
      Map<String, Object> job,
      String detail) {
    return new Attempt(
        a.attemptId(),
        a.ownerId(),
        a.idempotencyKey(),
        a.jobId(),
        a.fingerprint(),
        state,
        leaseId,
        epoch,
        expires,
        requestDigest,
        job,
        detail);
  }
}
