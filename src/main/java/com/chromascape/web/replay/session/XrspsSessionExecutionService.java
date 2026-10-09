package com.chromascape.web.replay.session;

import com.chromascape.web.replay.capability.CapabilityManifest;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Session API facade. Spring uses durable production execution. The explicit store/no-argument
 * constructors retain the counter-only diagnostic fixture for isolated lifecycle tests; they never
 * provide evidence of Fishing effects or authoritative world state.
 */
@Service
public class XrspsSessionExecutionService {

  private final XrspsSessionStore store;
  private final XrspsDurableSessionService durable;

  /** Production always uses the database and the pinned real-service worker. */
  @org.springframework.beans.factory.annotation.Autowired
  public XrspsSessionExecutionService(XrspsDurableSessionService durable) {
    this.durable = durable;
    this.store = null;
  }

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
    this.durable = null;
  }

  /** Creates a session and moves it to READY after scenario validation. */
  public XrspsSession createAndReady(
      XrspsSessionMode mode, String idempotencyKey, String scenarioRef) {
    if (durable != null) return durable.create(mode, idempotencyKey, scenarioRef).session();
    XrspsSession session = store.create(mode, idempotencyKey, scenarioRef);
    if (session.getStatus() == XrspsSessionStatus.CREATING) {
      session.markReady();
    }
    return session;
  }

  public XrspsSessionRepository.Creation createResult(
      XrspsSessionMode mode, String key, String scenario) {
    if (durable != null) return durable.create(mode, key, scenario);
    XrspsSession session = store.create(mode, key, scenario);
    boolean created = session.getStatus() == XrspsSessionStatus.CREATING;
    if (created) session.markReady();
    return new XrspsSessionRepository.Creation(session, created);
  }

  public XrspsSession get(UUID sessionId) {
    return durable != null ? durable.get(sessionId) : store.get(sessionId);
  }

  public XrspsSession start(UUID sessionId) {
    if (durable != null) return durable.transition(sessionId, XrspsSession::start);
    XrspsSession session = store.get(sessionId);
    session.start();
    return session;
  }

  public XrspsSession pause(UUID sessionId) {
    if (durable != null) return durable.transition(sessionId, XrspsSession::pause);
    XrspsSession session = store.get(sessionId);
    session.pause();
    return session;
  }

  public XrspsSession resume(UUID sessionId) {
    if (durable != null) return durable.transition(sessionId, XrspsSession::resume);
    XrspsSession session = store.get(sessionId);
    session.resume();
    return session;
  }

  public XrspsSession cancel(UUID sessionId, String reason) {
    if (durable != null) return durable.transition(sessionId, s -> s.cancel(reason));
    XrspsSession session = store.get(sessionId);
    session.cancel(reason);
    return session;
  }

  public XrspsSession stepTicks(UUID sessionId, int ticks) {
    if (durable != null) {
      durable.step(sessionId, ticks);
      return durable.get(sessionId);
    }
    XrspsSession session = store.get(sessionId);
    session.stepTicks(ticks);
    return session;
  }

  /**
   * Runs one Fishing journal headlessly to completion, mismatch, or session halt. The journal is
   * cached per session so a later {@link #stepJournal} can continue it while paused.
   */
  public XrspsRecordedRunReport runJournal(UUID sessionId, List<XrspsRecordedAction> journal) {
    if (durable != null) return durable.run(sessionId, journal);
    requireDispatchable(journal);
    journals.put(sessionId, List.copyOf(journal));
    XrspsSession session = store.get(sessionId);
    XrspsRecordedActionRunner runner =
        new XrspsRecordedActionRunner(store, session.getId(), journal);
    return runner.runRemaining();
  }

  /** Continues the cached journal with coordinated single-step execution while paused. */
  public XrspsRecordedRunReport stepJournal(UUID sessionId, int ticks) {
    if (durable != null) return durable.step(sessionId, ticks);
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
    if (durable != null) return durable.observe(sessionId);
    XrspsSession session = store.get(sessionId);
    return new Observation(
        session.getId(),
        session.getMode(),
        session.getStatus(),
        session.getTick(),
        session.getObservationRevision(),
        session.getEpoch(),
        CapabilityManifest.V1_SUPPORTED_ACTIONS,
        null,
        false);
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
      List<String> legalActions,
      com.fasterxml.jackson.databind.JsonNode domainState,
      boolean durable) {}
}
