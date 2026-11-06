package com.chromascape.web.aiplayer.routine.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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

  /** The attempt an (owner, idempotency key) pair names, if any. */
  Optional<Attempt> findByKey(String ownerId, String idempotencyKey);

  /**
   * What operators asked of an attempt and what its owner observed (SC-2106 R9.2, R9.5). Requests
   * are durable, so they reach the owning process wherever they were made.
   *
   * @param cancelRequested a cancel was asked for
   * @param pauseRequested the run should be parked (survives the attempt, for its recovery)
   * @param paused the owner observed the runner parked
   */
  record Control(boolean cancelRequested, boolean pauseRequested, boolean paused) {
    static final Control NONE = new Control(false, false, false);
  }

  /** The controls of {@code attemptId}; {@link Control#NONE} when there is no such attempt. */
  Control control(String attemptId);

  /** Records a cancel request; false when the attempt is not open (nothing left to cancel). */
  boolean requestCancel(String attemptId);

  /** Records a pause ({@code true}) or resume request; false when the attempt is not open. */
  boolean requestPause(String attemptId, boolean pause);

  /** The owner's report that the runner parked or resumed; fenced like every owner write. */
  void recordPaused(String attemptId, String leaseId, long epoch, boolean paused);

  /**
   * The worker session an attempt's runner is bound to, as the runner reported it (SC-2106 R4.2).
   * The worker lease appears only as a digest; the worker revision and the runner's own progress
   * are separate numbers.
   *
   * @param reason {@code CREATE} for a new session, {@code RESTORE} after a restore rotated its
   *     epoch and lease
   */
  record WorkerBinding(
      String bindingId,
      long generation,
      String attemptId,
      String requestDigest,
      String workerAuthority,
      String workerSessionId,
      long workerEpoch,
      String workerLeaseDigest,
      long workerRevision,
      long runnerSteps,
      long runnerIntents,
      String reason,
      Map<String, Object> workerIdentity,
      RecoveredFrom recoveredFrom) {

    /** A binding that names no recovered cut (every CREATE, and a RESTORE without P3). */
    public WorkerBinding(
        String bindingId,
        long generation,
        String attemptId,
        String requestDigest,
        String workerAuthority,
        String workerSessionId,
        long workerEpoch,
        String workerLeaseDigest,
        long workerRevision,
        long runnerSteps,
        long runnerIntents,
        String reason,
        Map<String, Object> workerIdentity) {
      this(
          bindingId,
          generation,
          attemptId,
          requestDigest,
          workerAuthority,
          workerSessionId,
          workerEpoch,
          workerLeaseDigest,
          workerRevision,
          runnerSteps,
          runnerIntents,
          reason,
          workerIdentity,
          null);
    }
  }

  /**
   * The cut a recovery restored, as its RESTORE binding reports it (P3, R4.3). {@code resolutions}
   * maps each unresolved command of the source attempt to {@code APPLIED} when the cut's worker had
   * accepted it, or {@code SUPERSEDED} when the restored world predates it.
   */
  record RecoveredFrom(
      String attemptId,
      long checkpointIndex,
      String checkpointChecksum,
      long workerEpoch,
      long workerRevision,
      Map<String, CommandState> resolutions) {}

  /** A binding that does not belong to the attempt, or is not its next generation (R4.2). */
  final class BindingRejectedException extends RuntimeException {
    public BindingRejectedException(String message) {
      super(message);
    }
  }

  /**
   * Accepts {@code binding} as the attempt's next generation, under the owner's fence (R4.2). It
   * commits only for a RUNNING attempt held by {@code (leaseId, epoch)}, whose request digest the
   * binding names, when the binding is exactly the next generation. Nothing is written otherwise.
   *
   * @throws StaleOwnerException when the presented lease or epoch is no longer current
   * @throws BindingRejectedException for any other mismatch
   */
  void recordBinding(String attemptId, String leaseId, long epoch, WorkerBinding binding);

  /** The attempt's current accepted binding; empty when it has none (including legacy attempts). */
  Optional<WorkerBinding> currentBinding(String attemptId);

  /** The worker calls the owner gates before the runner may send them (P3, R4.3). */
  Set<String> GATED_METHODS =
      Set.of(
          "session.candidate.execute",
          "session.candidate.advance",
          "session.candidate.restore",
          "session.candidate.resume");

  /** Command kinds a gated call may carry (P3, R4.3). */
  Set<String> COMMAND_KINDS = Set.of("EFFECT", "TIME", "LIFECYCLE");

  /** Lifecycle of one gated worker command (P3, R4.3). */
  enum CommandState {
    PERMITTED,
    APPLIED,
    REJECTED,
    UNKNOWN,
    /** A recovery restored a world from before this command; nothing it did continues. */
    SUPERSEDED
  }

  /**
   * A worker command the runner asks permission to send (P3, R4.3). {@code payloadDigest} is the
   * canonical-JSON digest of the exact call parameters; {@code bindingGeneration} names the worker
   * binding the command runs under.
   */
  record CommandIntent(
      String intentId,
      String commandId,
      String method,
      String kind,
      String payloadDigest,
      String attemptId,
      String requestDigest,
      long bindingGeneration,
      long expectedWorkerEpoch,
      long expectedWorkerRevision) {}

  /** What the worker answered to a permitted command (P3, R4.3). */
  record CommandReceipt(
      String intentId,
      String commandId,
      CommandState outcome,
      Long workerRevision,
      String receiptDigest) {}

  /** A recorded worker command and its current state (P3, R4.3). */
  record CommandRecord(
      String attemptId,
      String commandId,
      String intentId,
      String method,
      String kind,
      String payloadDigest,
      long bindingGeneration,
      long expectedWorkerEpoch,
      long expectedWorkerRevision,
      CommandState state,
      Long workerRevision,
      String receiptDigest,
      String resolvedByAttemptId) {}

  /** A command that does not belong to the attempt's current state (P3, R4.3). */
  class CommandRejectedException extends RuntimeException {
    public CommandRejectedException(String message) {
      super(message);
    }
  }

  /** The same command id was presented with a different payload (P3, R4.3). */
  final class CommandConflictException extends CommandRejectedException {
    public CommandConflictException(String message) {
      super(message);
    }
  }

  /**
   * Records {@code intent} as PERMITTED under the owner's fence before the runner may send it (P3,
   * R4.3). It commits only for a RUNNING attempt held by {@code (leaseId, epoch)}, whose request
   * digest and current binding generation the intent names, when the method is gated and no other
   * command of the attempt is open. The same command id with the same payload returns the existing
   * record without writing.
   *
   * @throws StaleOwnerException when the presented lease or epoch is no longer current
   * @throws CommandConflictException when the command id was recorded with another payload
   * @throws CommandRejectedException for any other mismatch
   */
  CommandRecord recordIntent(String attemptId, String leaseId, long epoch, CommandIntent intent);

  /**
   * Resolves a PERMITTED command from the worker's answer, under the owner's fence (P3, R4.3). A
   * receipt for an unknown command, another intent or an already resolved command is refused.
   *
   * @throws StaleOwnerException when the presented lease or epoch is no longer current
   * @throws CommandRejectedException for any other mismatch
   */
  CommandRecord recordReceipt(String attemptId, String leaseId, long epoch, CommandReceipt receipt);

  /** The attempt's recorded commands in permit order. */
  List<CommandRecord> commands(String attemptId);

  /** The genesis chain digest of an SC-1916 attempt journal. */
  String JOURNAL_GENESIS = "sha256:" + "0".repeat(64);

  /**
   * An attempt's managed journal head (R5.1): the last committed segment's sequence and chain
   * digest. Sequence 0 with the genesis chain means nothing is committed.
   */
  record JournalHead(
      String attemptId, long sequence, String chainDigest, long eventCount, boolean terminal) {
    static JournalHead empty(String attemptId) {
      return new JournalHead(attemptId, 0L, JOURNAL_GENESIS, 0L, false);
    }
  }

  /** One committed journal segment and the artifact holding its bytes (R5.1). */
  record JournalSegment(
      long sequence,
      String segmentDigest,
      String previousChainDigest,
      String chainDigest,
      long eventCount,
      String artifactId) {}

  /** A managed journal commit built on a head that is no longer current (R5.1). */
  final class JournalConflictException extends RuntimeException {
    public JournalConflictException(String message) {
      super(message);
    }
  }

  /** The attempt's managed journal head; {@link JournalHead#empty} when nothing is committed. */
  JournalHead journalHead(String attemptId);

  /** The attempt's committed journal segments in sequence order. */
  List<JournalSegment> journalSegments(String attemptId);

  /**
   * Commits {@code segments}, whose bytes are already in the artifact store, and advances the
   * managed head from {@code expected}, under the owner's fence (R5.1). It commits only for an open
   * attempt held by {@code (leaseId, epoch)}, when the managed head still equals {@code expected}
   * and the segments continue it without a gap. A terminal head is never extended.
   *
   * @throws StaleOwnerException when the presented lease or epoch is no longer current
   * @throws JournalConflictException when the head moved or the segments do not continue it
   */
  JournalHead commitJournal(
      String attemptId,
      String leaseId,
      long epoch,
      JournalHead expected,
      List<JournalSegment> segments,
      boolean terminal);

  /** The checks every store applies before committing segments onto {@code current} (R5.1). */
  static JournalHead continueJournal(
      JournalHead current, JournalHead expected, List<JournalSegment> segments, boolean terminal) {
    if (!current.equals(expected)) {
      throw new JournalConflictException(
          "commit was built on " + expected + " but the managed head is " + current);
    }
    if (current.terminal()) {
      throw new JournalConflictException("a terminal journal is never extended");
    }
    long sequence = current.sequence();
    String chain = current.chainDigest();
    long events = current.eventCount();
    for (JournalSegment segment : segments) {
      if (segment.sequence() != sequence + 1 || !chain.equals(segment.previousChainDigest())) {
        throw new JournalConflictException(
            "segment " + segment.sequence() + " does not continue the head at " + sequence);
      }
      sequence = segment.sequence();
      chain = segment.chainDigest();
      events += segment.eventCount();
    }
    if (sequence == 0) {
      throw new JournalConflictException("an empty journal cannot be committed");
    }
    return new JournalHead(current.attemptId(), sequence, chain, events, terminal);
  }

  /**
   * How a recovery's binding resolves the commands its source attempt left unresolved (P3, R4.3).
   * Only a RESTORE binding resolves, and it must name the source attempt and give an APPLIED or
   * SUPERSEDED verdict for exactly the source's unresolved commands; any other binding of a
   * recovery with unresolved source commands resolves nothing. Returns the verdicts to commit, or
   * an empty map.
   *
   * @throws BindingRejectedException when a RESTORE binding does not account for them exactly
   */
  static Map<String, CommandState> sourceResolutions(
      String recovers, List<CommandRecord> sourceUnresolved, WorkerBinding binding) {
    RecoveredFrom from = binding.recoveredFrom();
    if (recovers == null || !"RESTORE".equals(binding.reason())) {
      if (from != null) {
        throw new BindingRejectedException("only a recovery's RESTORE binding names a cut");
      }
      return Map.of();
    }
    if (from == null) {
      if (!sourceUnresolved.isEmpty()) {
        throw new BindingRejectedException(
            "attempt "
                + recovers
                + " left "
                + sourceUnresolved.size()
                + " unresolved command(s); the restored cut must resolve them");
      }
      return Map.of();
    }
    if (!recovers.equals(from.attemptId())) {
      throw new BindingRejectedException(
          "the restored cut is from " + from.attemptId() + ", not " + recovers);
    }
    Map<String, CommandState> verdicts = from.resolutions() == null ? Map.of() : from.resolutions();
    Set<String> expected = new TreeSet<>();
    sourceUnresolved.forEach(c -> expected.add(c.commandId()));
    if (!expected.equals(new TreeSet<>(verdicts.keySet()))) {
      throw new BindingRejectedException(
          "the restored cut resolves "
              + new TreeSet<>(verdicts.keySet())
              + ", the source left "
              + expected);
    }
    for (Map.Entry<String, CommandState> verdict : verdicts.entrySet()) {
      if (verdict.getValue() != CommandState.APPLIED
          && verdict.getValue() != CommandState.SUPERSEDED) {
        throw new BindingRejectedException(
            "command " + verdict.getKey() + " cannot be resolved as " + verdict.getValue());
      }
    }
    return verdicts;
  }

  /**
   * Refuses a recovery's effect or time command while its source attempt still has an unresolved
   * command (P3, R4.3); lifecycle commands (the restore itself) stay allowed.
   */
  static void rejectBeforeSourceResolved(
      String recovers, long sourceUnresolved, CommandIntent intent) {
    if (recovers != null && sourceUnresolved > 0 && !"LIFECYCLE".equals(intent.kind())) {
      throw new CommandRejectedException(
          "attempt "
              + recovers
              + " still has "
              + sourceUnresolved
              + " unresolved command(s); the recovery's RESTORE binding resolves them first");
    }
  }

  /**
   * The checks every store applies to an intent before recording it (P3, R4.3); the caller has
   * already verified the owner's fence.
   */
  static void rejectForeignIntent(
      String attemptId,
      String state,
      String requestDigest,
      long currentBindingGeneration,
      CommandIntent intent) {
    if (intent == null) {
      throw new CommandRejectedException("no intent");
    }
    if (!"RUNNING".equals(state)) {
      throw new CommandRejectedException("attempt " + attemptId + " is " + state + ", not RUNNING");
    }
    if (!attemptId.equals(intent.attemptId())) {
      throw new CommandRejectedException(
          "intent names attempt " + intent.attemptId() + ", not " + attemptId);
    }
    if (requestDigest == null || !requestDigest.equals(intent.requestDigest())) {
      throw new CommandRejectedException("intent names another request digest");
    }
    if (!GATED_METHODS.contains(intent.method())) {
      throw new CommandRejectedException("method " + intent.method() + " is not gated");
    }
    if (!COMMAND_KINDS.contains(intent.kind())) {
      throw new CommandRejectedException("unknown command kind " + intent.kind());
    }
    if (intent.commandId() == null
        || intent.commandId().isBlank()
        || intent.intentId() == null
        || intent.intentId().isBlank()) {
      throw new CommandRejectedException("intent and command ids are required");
    }
    if (intent.payloadDigest() == null
        || !intent.payloadDigest().matches("^sha256:[0-9a-f]{64}$")) {
      throw new CommandRejectedException("payload digest must be a sha256 content address");
    }
    if (currentBindingGeneration < 1 || intent.bindingGeneration() != currentBindingGeneration) {
      throw new CommandRejectedException(
          "intent names binding generation "
              + intent.bindingGeneration()
              + ", the attempt is at "
              + currentBindingGeneration);
    }
    if (intent.expectedWorkerEpoch() < 0 || intent.expectedWorkerRevision() < 0) {
      throw new CommandRejectedException("expected worker epoch and revision must be non-negative");
    }
  }
}
