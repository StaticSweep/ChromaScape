package com.chromascape.web.replay.session;

import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Headless session execution against the authoritative session state: one worker, one mutable
 * scenario world, one mode.
 *
 * <p>Semantic dispatch gate: only {@link CapabilityManifest#V1_SUPPORTED_ACTIONS} action types are
 * dispatched. Anything else is refused before journaling with {@code ACTION_UNSUPPORTED} and never
 * mutates the session. Ticks only move forward through the recorded-action runner while RUNNING (or
 * the coordinated step path while PAUSED); the store journal plus lease fencing is the durable
 * truth a fresh worker replays (applied actions return DUPLICATE with no duplicate effects).
 *
 * <p>Slice bounds: the journal backing is in-memory with lease fencing; durable database backing,
 * production kernel dispatch, client attachment, provider auth and multi-operator ownership arrive
 * in later phases. External callers never supply a lease: the service fills the current session
 * lease on every submission.
 */
@Service
public class XrspsSessionExecutionService {

  private final XrspsSessionStore store;
  private final ConcurrentHashMap<UUID, List<XrspsRecordedAction>> journals =
      new ConcurrentHashMap<>();

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

  /** Creates a session and moves it to READY after scenario validation. */
  public XrspsSession createAndReady(
      XrspsSessionMode mode, String idempotencyKey, String scenarioRef) {
    XrspsSession session = store.create(mode, idempotencyKey, scenarioRef);
    if (session.getStatus() == XrspsSessionStatus.CREATING) {
      session.markReady();
    }
    return session;
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

  /**
   * Runs one Fishing journal headlessly to completion, mismatch, or session halt. The journal is
   * cached per session so a later {@link #stepJournal} can continue it while paused.
   */
  public XrspsRecordedRunReport runJournal(UUID sessionId, List<XrspsRecordedAction> journal) {
    requireDispatchable(journal);
    journals.put(sessionId, List.copyOf(journal));
    XrspsSession session = store.get(sessionId);
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(store, session.getId(), journal);
    return runner.runRemaining();
  }

  /** Continues the cached journal with coordinated single-step execution while paused. */
  public XrspsRecordedRunReport stepJournal(UUID sessionId, int ticks) {
    List<XrspsRecordedAction> journal = journals.get(sessionId);
    if (journal == null) {
      throw ReplayException.invalid(
          ReplayErrorCode.VALIDATION_ERROR, "No journal has been run for this session");
    }
    XrspsSession session = store.get(sessionId);
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(store, session.getId(), journal);
    return runner.stepPaused(ticks);
  }

  /** Authoritative observation: tick, revision, epoch, lifecycle and the legal action surface. */
  public Observation observe(UUID sessionId) {
    XrspsSession session = store.get(sessionId);
    return new Observation(
        session.getId(),
        session.getMode(),
        session.getStatus(),
        session.getTick(),
        session.getObservationRevision(),
        session.getEpoch(),
        CapabilityManifest.V1_SUPPORTED_ACTIONS);
  }

  private static void requireDispatchable(List<XrspsRecordedAction> journal) {
    if (journal == null || journal.isEmpty()) {
      throw ReplayException.invalid(
          ReplayErrorCode.VALIDATION_ERROR, "A non-empty action journal is required");
    }
    for (XrspsRecordedAction action : journal) {
      if (action == null
          || action.actionType() == null
          || !CapabilityManifest.V1_SUPPORTED_ACTIONS.contains(action.actionType())) {
        throw ReplayException.invalid(
            ReplayErrorCode.ACTION_UNSUPPORTED,
            "Unsupported action type " + (action == null ? "null" : action.actionType()));
      }
    }
  }

  /** Authoritative observation for one session. */
  public record Observation(
      UUID sessionId,
      XrspsSessionMode mode,
      XrspsSessionStatus status,
      long tick,
      long observationRevision,
      long epoch,
      List<String> legalActions) {}
}
