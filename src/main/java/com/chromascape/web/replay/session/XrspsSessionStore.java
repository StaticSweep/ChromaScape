package com.chromascape.web.replay.session;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;

/**
 * Owns session identity, the per-session command journal and lease fencing.
 *
 * <p>Identical idempotent requests return the existing session or receipt. Reuse of an idempotency
 * key or command identity with different content fails instead of duplicating effects. Persistence
 * backing is intentionally out of scope for this slice: the journal contract here is what a durable
 * store must honor.
 */
public class XrspsSessionStore {

  private static final String KEY_PATTERN = "^[A-Za-z0-9._:-]+$";

  private record JournalEntry(
      String actionType,
      XrspsCommandSource source,
      String payloadHash,
      XrspsSessionReceipt receipt) {}

  private static final class SessionEntry {
    final XrspsSession session;
    final Map<String, JournalEntry> journal = new HashMap<>();
    List<XrspsRecordedAction> recordedJournal;
    XrspsRecordedActionRunner recordedRunner;

    SessionEntry(XrspsSession session) {
      this.session = session;
    }
  }

  private final Map<UUID, SessionEntry> sessions = new ConcurrentHashMap<>();

  private record CreationKey(String ownerId, String idempotencyKey) {}

  private final Map<CreationKey, UUID> sessionsByIdempotencyKey = new ConcurrentHashMap<>();

  /**
   * Reserves one session for {@code idempotencyKey}. A known key with identical mode and scenario
   * returns the existing session; a known key with different content fails. The check-and-insert is
   * atomic so simultaneous requests cannot duplicate sessions.
   */
  public synchronized XrspsSession create(
      XrspsSessionMode mode, String idempotencyKey, String scenarioRef) {
    return create(mode, idempotencyKey, scenarioRef, null);
  }

  public synchronized XrspsSession create(
      XrspsSessionMode mode, String idempotencyKey, String scenarioRef, String ownerId) {
    requireKey(idempotencyKey);
    CreationKey key = new CreationKey(ownerId, idempotencyKey);
    if (mode == null || scenarioRef == null || scenarioRef.isBlank()) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.BAD_REQUEST,
          false,
          "Session mode and scenario reference are required",
          "Provide the execution mode and the scenario or task identity");
    }
    UUID existingId = sessionsByIdempotencyKey.get(key);
    if (existingId != null) {
      SessionEntry existing = sessions.get(existingId);
      if (existing != null
          && existing.session.getMode() == mode
          && existing.session.getScenarioRef().equals(scenarioRef)) {
        return existing.session;
      }
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.CONFLICT,
          false,
          "Idempotency key reused with different content",
          "Reuse the identical mode and scenario reference, or use a new idempotency key");
    }
    XrspsSession session =
        new XrspsSession(UUID.randomUUID(), mode, idempotencyKey, scenarioRef, ownerId);
    sessions.put(session.getId(), new SessionEntry(session));
    sessionsByIdempotencyKey.put(key, session.getId());
    return session;
  }

  public XrspsSession get(UUID sessionId) {
    SessionEntry entry = sessions.get(sessionId);
    if (entry == null) {
      throw new ReplayException(
          ReplayErrorCode.RECORDING_NOT_FOUND,
          HttpStatus.NOT_FOUND,
          false,
          "xRSPS session not found",
          "Create the session before inspecting it or submitting commands");
    }
    return entry.session;
  }

  /** Hides foreign-session existence using the exact same error as a missing owned session. */
  public XrspsSession getOwned(UUID sessionId, String ownerId) {
    SessionEntry entry = sessions.get(sessionId);
    if (entry == null || ownerId == null || !ownerId.equals(entry.session.getOwnerId())) {
      throw ReplayException.notFound(
          ReplayErrorCode.RECORDING_NOT_FOUND, "xRSPS session not found");
    }
    return entry.session;
  }

  /** Validates before binding one immutable journal and runner to this in-memory session. */
  public XrspsRecordedActionRunner bindRecordedJournal(
      UUID sessionId, List<XrspsRecordedAction> journal) {
    XrspsSession session = get(sessionId);
    synchronized (session) {
      XrspsRecordedActionRunner candidate = new XrspsRecordedActionRunner(this, sessionId, journal);
      List<XrspsRecordedAction> validated = List.copyOf(journal);
      SessionEntry entry = sessions.get(sessionId);
      if (entry.recordedRunner == null) {
        entry.recordedJournal = validated;
        entry.recordedRunner = candidate;
      } else if (!entry.recordedJournal.equals(validated)) {
        throw ReplayException.conflict(
            ReplayErrorCode.VALIDATION_ERROR,
            "Session journal is immutable; create another session for changed actions");
      }
      return entry.recordedRunner;
    }
  }

  public XrspsRecordedActionRunner recordedRunner(UUID sessionId) {
    XrspsSession session = get(sessionId);
    synchronized (session) {
      XrspsRecordedActionRunner runner = sessions.get(sessionId).recordedRunner;
      if (runner == null)
        throw ReplayException.invalid(
            ReplayErrorCode.VALIDATION_ERROR, "No journal has been run for this session");
      return runner;
    }
  }

  /**
   * Submits one semantic action. The decision order is fixed: session liveness, lease fence,
   * duplicate identity, revision match, then apply. Rejections never mutate the session.
   */
  public XrspsSessionReceipt submit(XrspsSessionCommand command) {
    SessionEntry entry = sessions.get(command.sessionId());
    if (entry == null) {
      throw new ReplayException(
          ReplayErrorCode.RECORDING_NOT_FOUND,
          HttpStatus.NOT_FOUND,
          false,
          "xRSPS session not found",
          "Create the session before submitting commands");
    }
    synchronized (entry.session) {
      XrspsSession session = entry.session;
      if (session.getStatus() != XrspsSessionStatus.RUNNING) {
        return new XrspsSessionReceipt(
            command.commandId(),
            session.getId(),
            XrspsReceiptOutcome.REJECTED_SESSION_NOT_RUNNING,
            session.getTick(),
            session.getObservationRevision(),
            "Session is " + session.getStatus() + "; commands require RUNNING");
      }
      XrspsSessionReceipt fenced = checkLease(session, command);
      if (fenced != null) {
        return fenced;
      }
      JournalEntry prior = entry.journal.get(command.commandId());
      if (prior != null) {
        if (isIdenticalContent(prior, command)) {
          XrspsSessionReceipt original = prior.receipt();
          return new XrspsSessionReceipt(
              original.commandId(),
              original.sessionId(),
              XrspsReceiptOutcome.DUPLICATE,
              original.tick(),
              original.revision(),
              "Identical retry; original receipt replayed without re-applying");
        }
        return new XrspsSessionReceipt(
            command.commandId(),
            session.getId(),
            XrspsReceiptOutcome.REJECTED_CONFLICT,
            session.getTick(),
            session.getObservationRevision(),
            "Command identity reused with conflicting content");
      }
      if (command.expectedRevision() != session.getObservationRevision()) {
        return new XrspsSessionReceipt(
            command.commandId(),
            session.getId(),
            XrspsReceiptOutcome.REJECTED_STALE_REVISION,
            session.getTick(),
            session.getObservationRevision(),
            "Expected revision "
                + command.expectedRevision()
                + " but session is at "
                + session.getObservationRevision());
      }
      session.applyAcceptedCommand();
      XrspsSessionReceipt receipt =
          new XrspsSessionReceipt(
              command.commandId(),
              session.getId(),
              XrspsReceiptOutcome.ACCEPTED,
              session.getTick(),
              session.getObservationRevision(),
              null);
      entry.journal.put(
          command.commandId(),
          new JournalEntry(command.actionType(), command.source(), command.payloadHash(), receipt));
      return receipt;
    }
  }

  /**
   * Submits one semantic action from the coordinated single-step path. Only legal while paused; the
   * decision order matches {@link #submit}: lease fence, duplicate identity, revision match, then
   * apply. Rejections never mutate the session.
   */
  public XrspsSessionReceipt submitWhilePaused(XrspsSessionCommand command) {
    SessionEntry entry = sessions.get(command.sessionId());
    if (entry == null) {
      throw new ReplayException(
          ReplayErrorCode.RECORDING_NOT_FOUND,
          HttpStatus.NOT_FOUND,
          false,
          "xRSPS session not found",
          "Create the session before submitting commands");
    }
    synchronized (entry.session) {
      XrspsSession session = entry.session;
      if (session.getStatus() != XrspsSessionStatus.PAUSED) {
        return new XrspsSessionReceipt(
            command.commandId(),
            session.getId(),
            XrspsReceiptOutcome.REJECTED_SESSION_NOT_RUNNING,
            session.getTick(),
            session.getObservationRevision(),
            "Session is " + session.getStatus() + "; stepped commands require PAUSED");
      }
      XrspsSessionReceipt fenced = checkLease(session, command);
      if (fenced != null) {
        return fenced;
      }
      JournalEntry prior = entry.journal.get(command.commandId());
      if (prior != null) {
        if (isIdenticalContent(prior, command)) {
          XrspsSessionReceipt original = prior.receipt();
          return new XrspsSessionReceipt(
              original.commandId(),
              original.sessionId(),
              XrspsReceiptOutcome.DUPLICATE,
              original.tick(),
              original.revision(),
              "Identical retry; original receipt replayed without re-applying");
        }
        return new XrspsSessionReceipt(
            command.commandId(),
            session.getId(),
            XrspsReceiptOutcome.REJECTED_CONFLICT,
            session.getTick(),
            session.getObservationRevision(),
            "Command identity reused with conflicting content");
      }
      if (command.expectedRevision() != session.getObservationRevision()) {
        return new XrspsSessionReceipt(
            command.commandId(),
            session.getId(),
            XrspsReceiptOutcome.REJECTED_STALE_REVISION,
            session.getTick(),
            session.getObservationRevision(),
            "Expected revision "
                + command.expectedRevision()
                + " but session is at "
                + session.getObservationRevision());
      }
      session.applySteppedCommand();
      XrspsSessionReceipt receipt =
          new XrspsSessionReceipt(
              command.commandId(),
              session.getId(),
              XrspsReceiptOutcome.ACCEPTED,
              session.getTick(),
              session.getObservationRevision(),
              null);
      entry.journal.put(
          command.commandId(),
          new JournalEntry(command.actionType(), command.source(), command.payloadHash(), receipt));
      return receipt;
    }
  }

  /**
   * Returns true when {@code commandId} is already journaled for the session with identical type,
   * source and payload. Lets a fresh worker skip the late check for replayed actions without
   * re-applying effects; never-applied overdue actions still count as late.
   */
  public boolean isJournaledIdentical(
      java.util.UUID sessionId,
      String commandId,
      String actionType,
      XrspsCommandSource source,
      String payloadHash) {
    SessionEntry entry = sessions.get(sessionId);
    if (entry == null || commandId == null) {
      return false;
    }
    synchronized (entry.session) {
      JournalEntry prior = entry.journal.get(commandId);
      return prior != null
          && prior.actionType().equals(actionType)
          && prior.source() == source
          && prior.payloadHash().equals(payloadHash);
    }
  }

  private static boolean isIdenticalContent(JournalEntry prior, XrspsSessionCommand command) {
    return prior.actionType().equals(command.actionType())
        && prior.source() == command.source()
        && prior.payloadHash().equals(command.payloadHash());
  }

  private static XrspsSessionReceipt checkLease(XrspsSession session, XrspsSessionCommand command) {
    if (command.leaseId() == null || !command.leaseId().equals(session.getLeaseId())) {
      return new XrspsSessionReceipt(
          command.commandId(),
          session.getId(),
          XrspsReceiptOutcome.REJECTED_STALE_LEASE,
          session.getTick(),
          session.getObservationRevision(),
          "Command lease does not match the session lease; refresh from the session owner");
    }
    return null;
  }

  private static void requireKey(String idempotencyKey) {
    if (idempotencyKey == null
        || idempotencyKey.length() < 8
        || idempotencyKey.length() > 128
        || !idempotencyKey.matches(KEY_PATTERN)) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          HttpStatus.BAD_REQUEST,
          false,
          "Idempotency key is invalid",
          "Use 8-128 characters from [A-Za-z0-9._:-]");
    }
  }
}
