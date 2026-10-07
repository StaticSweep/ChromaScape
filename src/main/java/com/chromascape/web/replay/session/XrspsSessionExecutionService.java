package com.chromascape.web.replay.session;

import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * In-memory session lifecycle and recorded-journal scheduling. This slice neither loads a scenario
 * nor dispatches game actions to the xRSPS kernel. Counter/receipt acceptance is not Fishing proof.
 * Database durability, kernel dispatch and verified world recovery remain separate delivery work.
 */
@Service
public class XrspsSessionExecutionService {

  // Model-only alias admission does not expand any worker's attested capability profile.
  private static final List<String> ADMITTED_ACTIONS =
      java.util.stream.Stream.concat(
              CapabilityManifest.V1_SUPPORTED_ACTIONS.stream(),
              java.util.stream.Stream.of("INTERACT_FISHING_SPOT"))
          .distinct()
          .toList();

  private final XrspsSessionStore store;

  public XrspsSessionExecutionService() {
    this(new XrspsSessionStore());
  }

  public XrspsSessionExecutionService(XrspsSessionStore store) {
    if (store == null) {
      throw new ReplayException(
          ReplayErrorCode.VALIDATION_ERROR,
          org.springframework.http.HttpStatus.BAD_REQUEST,
          false,
          "Session store is required",
          "Provide the session store");
    }
    this.store = store;
  }

  /** Registers an in-memory scenario reference; no runtime scenario validation is claimed. */
  public XrspsSession createAndReady(
      XrspsSessionMode mode, String idempotencyKey, String scenarioRef) {
    return createAndReady(mode, idempotencyKey, scenarioRef, null);
  }

  public XrspsSession createAndReady(
      XrspsSessionMode mode, String idempotencyKey, String scenarioRef, String ownerId) {
    XrspsSession session = store.create(mode, idempotencyKey, scenarioRef, ownerId);
    synchronized (session) {
      if (session.getStatus() == XrspsSessionStatus.CREATING) session.markReady();
    }
    return session;
  }

  /** Missing and foreign sessions use the same 404 response. */
  public XrspsSession getOwned(UUID sessionId, String ownerId) {
    return store.getOwned(sessionId, ownerId);
  }

  public XrspsSession get(UUID sessionId) {
    return store.get(sessionId);
  }

  public XrspsSession start(UUID sessionId) {
    XrspsSession session = store.get(sessionId);
    session.start();
    return session;
  }

  public XrspsSession pause(UUID sessionId) {
    XrspsSession session = store.get(sessionId);
    session.pause();
    return session;
  }

  public XrspsSession resume(UUID sessionId) {
    XrspsSession session = store.get(sessionId);
    session.resume();
    return session;
  }

  public XrspsSession cancel(UUID sessionId, String reason) {
    XrspsSession session = store.get(sessionId);
    session.cancel(reason);
    return session;
  }

  public XrspsSession stepTicks(UUID sessionId, int ticks) {
    XrspsSession session = store.get(sessionId);
    session.stepTicks(ticks);
    return session;
  }

  /** Binds a fully validated immutable journal once and retains its cursor and first mismatch. */
  public XrspsRecordedRunReport runJournal(UUID sessionId, List<XrspsRecordedAction> journal) {
    store.get(sessionId);
    requireDispatchable(journal);
    // Binding releases the lifecycle monitor before the runner monitor is acquired.
    return store.bindRecordedJournal(sessionId, journal).runRemaining();
  }

  /** Continues the session's retained runner through the coordinated paused-step path. */
  public XrspsRecordedRunReport stepJournal(UUID sessionId, int ticks) {
    return store.recordedRunner(sessionId).stepPaused(ticks);
  }

  /** Session counters and admitted action vocabulary; not an authoritative game observation. */
  public Observation observe(UUID sessionId) {
    XrspsSession session = store.get(sessionId);
    return new Observation(
        session.getId(),
        session.getMode(),
        session.getStatus(),
        session.getTick(),
        session.getObservationRevision(),
        session.getEpoch(),
        ADMITTED_ACTIONS);
  }

  private static void requireDispatchable(List<XrspsRecordedAction> journal) {
    if (journal == null || journal.isEmpty()) {
      throw ReplayException.invalid(
          ReplayErrorCode.VALIDATION_ERROR, "A non-empty action journal is required");
    }
    for (XrspsRecordedAction action : journal) {
      if (action == null
          || action.actionType() == null
          || !ADMITTED_ACTIONS.contains(action.actionType())) {
        throw ReplayException.invalid(
            ReplayErrorCode.ACTION_UNSUPPORTED,
            "Unsupported action type " + (action == null ? "null" : action.actionType()));
      }
    }
  }

  /** In-memory session observation. */
  public record Observation(
      UUID sessionId,
      XrspsSessionMode mode,
      XrspsSessionStatus status,
      long tick,
      long observationRevision,
      long epoch,
      List<String> legalActions) {}
}
