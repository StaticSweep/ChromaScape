package com.chromascape.web.aiplayer.routine.service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Test double with the same fencing rules as {@link JdbcCandidateAttemptStore}. */
final class InMemoryCandidateAttemptStore implements CandidateAttemptStore {
  private static final EnumSet<State> CLAIMING =
      EnumSet.of(State.RESERVED, State.RUNNING, State.COMPLETED, State.UNCERTAIN);

  final Map<String, Attempt> byId = new LinkedHashMap<>();

  /** Recovery attempt id to the attempt it recovers, as the V73 {@code recovers} column. */
  final Map<String, String> recovers = new LinkedHashMap<>();

  int launchesMarked;

  /** The V74 control columns, by attempt id. */
  final Map<String, Control> controls = new LinkedHashMap<>();

  /**
   * When set, recording a result fails as a lost database connection would (R6 metadata commit).
   */
  RuntimeException finishFailure;

  @Override
  public synchronized Reservation reserveRecovery(
      String ownerId,
      String idempotencyKey,
      String jobId,
      String fingerprint,
      Duration lease,
      String recovered) {
    for (Attempt existing : byId.values()) {
      if (existing.ownerId().equals(ownerId) && existing.idempotencyKey().equals(idempotencyKey)) {
        return new Reservation(existing, false);
      }
    }
    Set<String> ancestors = new HashSet<>();
    String root = recovered;
    for (String id = recovered; id != null; id = recovers.get(id)) {
      ancestors.add(id);
      root = id;
    }
    for (Attempt claim : byId.values()) {
      String claimRoot = claim.attemptId();
      while (recovers.containsKey(claimRoot)) {
        claimRoot = recovers.get(claimRoot);
      }
      if (claimRoot.equals(root)
          && !ancestors.contains(claim.attemptId())
          && CLAIMING.contains(claim.state())) {
        throw new RecoveryClaimedException(recovered, claim.attemptId());
      }
    }
    Reservation reservation = reserve(ownerId, idempotencyKey, jobId, fingerprint, lease);
    recovers.put(reservation.attempt().attemptId(), recovered);
    return reservation;
  }

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
    if (finishFailure != null) {
      throw finishFailure;
    }
    Attempt a = held(attemptId, leaseId, epoch);
    Control c = control(attemptId);
    controls.put(attemptId, new Control(c.cancelRequested(), c.pauseRequested(), false));
    return put(with(a, state, null, a.epoch(), null, a.requestDigest(), job, detail));
  }

  @Override
  public synchronized int reconcileExpired(Instant now) {
    int count = 0;
    for (Attempt a : byId.values().toArray(new Attempt[0])) {
      if (a.state().open() && a.leaseExpiresAt() != null && a.leaseExpiresAt().isBefore(now)) {
        Control c = control(a.attemptId());
        controls.put(a.attemptId(), new Control(c.cancelRequested(), c.pauseRequested(), false));
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
        java.util.List<CommandRecord> recorded = commands.get(a.attemptId());
        if (recorded != null) {
          recorded.replaceAll(
              r ->
                  r.state() == CommandState.PERMITTED
                      ? resolved(r, CommandState.UNKNOWN, null)
                      : r);
        }
        count++;
      }
    }
    return count;
  }

  @Override
  public synchronized Optional<Attempt> find(String attemptId) {
    return Optional.ofNullable(byId.get(attemptId));
  }

  @Override
  public synchronized Optional<Attempt> findByKey(String ownerId, String idempotencyKey) {
    return byId.values().stream()
        .filter(a -> a.ownerId().equals(ownerId) && a.idempotencyKey().equals(idempotencyKey))
        .findFirst();
  }

  @Override
  public synchronized Control control(String attemptId) {
    return controls.getOrDefault(attemptId, Control.NONE);
  }

  @Override
  public synchronized boolean requestCancel(String attemptId) {
    Attempt a = byId.get(attemptId);
    if (a == null || !a.state().open()) {
      return false;
    }
    Control c = control(attemptId);
    controls.put(attemptId, new Control(true, c.pauseRequested(), c.paused()));
    return true;
  }

  @Override
  public synchronized boolean requestPause(String attemptId, boolean pause) {
    Attempt a = byId.get(attemptId);
    if (a == null || !a.state().open()) {
      return false;
    }
    Control c = control(attemptId);
    controls.put(attemptId, new Control(c.cancelRequested(), pause, c.paused()));
    return true;
  }

  @Override
  public synchronized void recordPaused(
      String attemptId, String leaseId, long epoch, boolean paused) {
    Attempt a = held(attemptId, leaseId, epoch);
    if (a.state() != State.RUNNING) {
      throw new StaleOwnerException(attemptId + " is " + a.state());
    }
    Control c = control(attemptId);
    controls.put(attemptId, new Control(c.cancelRequested(), c.pauseRequested(), paused));
  }

  /** Accepted worker bindings by attempt id, in generation order (the V75 table). */
  final Map<String, java.util.List<WorkerBinding>> bindings = new LinkedHashMap<>();

  @Override
  public synchronized void recordBinding(
      String attemptId, String leaseId, long epoch, WorkerBinding binding) {
    Attempt a = byId.get(attemptId);
    if (a == null) {
      throw new BindingRejectedException("attempt " + attemptId + " does not exist");
    }
    held(attemptId, leaseId, epoch);
    if (binding == null) {
      throw new BindingRejectedException("no binding");
    }
    java.util.List<WorkerBinding> accepted =
        bindings.computeIfAbsent(attemptId, id -> new java.util.ArrayList<>());
    JdbcCandidateAttemptStore.rejectForeignBinding(
        attemptId, a.state().name(), a.requestDigest(), accepted.size(), binding);
    for (java.util.List<WorkerBinding> other : bindings.values()) {
      for (WorkerBinding existing : other) {
        if (existing.bindingId().equals(binding.bindingId())) {
          throw new BindingRejectedException("binding " + binding.bindingId() + " already used");
        }
      }
    }
    accepted.add(binding);
  }

  @Override
  public synchronized Optional<WorkerBinding> currentBinding(String attemptId) {
    java.util.List<WorkerBinding> accepted = bindings.get(attemptId);
    return accepted == null || accepted.isEmpty()
        ? Optional.empty()
        : Optional.of(accepted.get(accepted.size() - 1));
  }

  /** The P3 command rows, by attempt id, in permit order. */
  final Map<String, java.util.List<CommandRecord>> commands = new LinkedHashMap<>();

  @Override
  public synchronized CommandRecord recordIntent(
      String attemptId, String leaseId, long epoch, CommandIntent intent) {
    Attempt a = byId.get(attemptId);
    if (a == null) {
      throw new CommandRejectedException("attempt " + attemptId + " does not exist");
    }
    held(attemptId, leaseId, epoch);
    java.util.List<WorkerBinding> accepted = bindings.get(attemptId);
    CandidateAttemptStore.rejectForeignIntent(
        attemptId,
        a.state().name(),
        a.requestDigest(),
        accepted == null ? 0 : accepted.size(),
        intent);
    java.util.List<CommandRecord> recorded =
        commands.computeIfAbsent(attemptId, id -> new java.util.ArrayList<>());
    for (CommandRecord r : recorded) {
      if (r.commandId().equals(intent.commandId())) {
        if (!r.payloadDigest().equals(intent.payloadDigest())
            || !r.method().equals(intent.method())) {
          throw new CommandConflictException(
              "command " + intent.commandId() + " was recorded with another payload");
        }
        return r;
      }
    }
    for (java.util.List<CommandRecord> other : commands.values()) {
      for (CommandRecord r : other) {
        if (r.intentId().equals(intent.intentId())) {
          throw new CommandRejectedException("intent " + intent.intentId() + " already used");
        }
      }
    }
    if (recorded.stream().anyMatch(r -> r.state() == CommandState.PERMITTED)) {
      throw new CommandRejectedException("attempt " + attemptId + " already has an open command");
    }
    CommandRecord record =
        new CommandRecord(
            attemptId,
            intent.commandId(),
            intent.intentId(),
            intent.method(),
            intent.kind(),
            intent.payloadDigest(),
            intent.bindingGeneration(),
            intent.expectedWorkerEpoch(),
            intent.expectedWorkerRevision(),
            CommandState.PERMITTED,
            null,
            null);
    recorded.add(record);
    return record;
  }

  @Override
  public synchronized CommandRecord recordReceipt(
      String attemptId, String leaseId, long epoch, CommandReceipt receipt) {
    if (receipt == null
        || receipt.outcome() == null
        || receipt.outcome() == CommandState.PERMITTED) {
      throw new CommandRejectedException("a receipt must resolve the command");
    }
    if (!byId.containsKey(attemptId)) {
      throw new CommandRejectedException("attempt " + attemptId + " does not exist");
    }
    held(attemptId, leaseId, epoch);
    java.util.List<CommandRecord> recorded = commands.get(attemptId);
    if (recorded != null) {
      for (int i = 0; i < recorded.size(); i++) {
        CommandRecord r = recorded.get(i);
        if (!r.commandId().equals(receipt.commandId())) {
          continue;
        }
        if (!r.intentId().equals(receipt.intentId())) {
          throw new CommandRejectedException("receipt names another intent");
        }
        if (r.state() != CommandState.PERMITTED) {
          throw new CommandRejectedException(
              "command " + receipt.commandId() + " is already " + r.state());
        }
        CommandRecord done = resolved(r, receipt.outcome(), receipt);
        recorded.set(i, done);
        return done;
      }
    }
    throw new CommandRejectedException(
        "attempt " + attemptId + " has no command " + receipt.commandId());
  }

  @Override
  public synchronized java.util.List<CommandRecord> commands(String attemptId) {
    java.util.List<CommandRecord> recorded = commands.get(attemptId);
    return recorded == null ? java.util.List.of() : java.util.List.copyOf(recorded);
  }

  private static CommandRecord resolved(
      CommandRecord r, CommandState state, CommandReceipt receipt) {
    return new CommandRecord(
        r.attemptId(),
        r.commandId(),
        r.intentId(),
        r.method(),
        r.kind(),
        r.payloadDigest(),
        r.bindingGeneration(),
        r.expectedWorkerEpoch(),
        r.expectedWorkerRevision(),
        state,
        receipt == null ? null : receipt.workerRevision(),
        receipt == null ? null : receipt.receiptDigest());
  }

  /** The attempt's lease runs out; nothing is reconciled yet. */
  synchronized void expire(String attemptId) {
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
