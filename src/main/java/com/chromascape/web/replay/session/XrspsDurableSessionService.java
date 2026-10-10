package com.chromascape.web.replay.session;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * Durable recorded execution through the existing authoritative xRSPS worker. Each action commits
 * checkpoint + receipt under a database row lock. A crashed or rolled-back attempt loses its
 * private world; only the committed checkpoint is restored on retry. An action hash alone is never
 * executable. AI mode and client attachment remain unavailable in this slice.
 */
@Service
public class XrspsDurableSessionService {
  private static final int MAX_JOURNAL_ACTIONS = 1000;
  private static final long MAX_TICK_ADVANCE = 10000;
  private static final Set<ReplayErrorCode> SEMANTIC_ERRORS =
      Set.of(
          ReplayErrorCode.ACTION_UNSUPPORTED,
          ReplayErrorCode.ACTION_TARGET_UNRESOLVED,
          ReplayErrorCode.ACTION_AMBIGUOUS,
          ReplayErrorCode.ACTION_TARGET_NOT_FOUND,
          ReplayErrorCode.ACTION_PRECONDITION_FAILED,
          ReplayErrorCode.SCHEMA_VERSION_UNSUPPORTED);
  private final XrspsSessionRepository repository;
  private final ScenarioManifestRepository manifests;
  private final XrspsSessionWorker workers;
  private final ObjectMapper mapper;
  private final CanonicalProjectionService canonical;
  private final com.chromascape.web.replay.security.ReplayOwnershipService ownership;

  public XrspsDurableSessionService(
      XrspsSessionRepository repository,
      ScenarioManifestRepository manifests,
      XrspsSessionWorker workers,
      ObjectMapper mapper,
      CanonicalProjectionService canonical,
      com.chromascape.web.replay.security.ReplayOwnershipService ownership) {
    this.repository = repository;
    this.manifests = manifests;
    this.workers = workers;
    this.mapper = mapper;
    this.canonical = canonical;
    this.ownership = ownership;
  }

  public XrspsSessionRepository.Creation create(
      XrspsSessionMode mode, String key, String scenario) {
    return create(mode, key, scenario, "diagnostic");
  }

  public XrspsSessionRepository.Creation create(
      XrspsSessionMode mode, String key, String scenario, String ownerId) {
    if (mode != XrspsSessionMode.RECORDED_ACTIONS)
      throw ReplayException.invalid(
          ReplayErrorCode.ACTION_UNSUPPORTED, "AI session execution is not available yet");
    return repository.create(
        mode,
        key,
        scenario,
        ownerId,
        session -> {
          UUID manifestId;
          try {
            manifestId = UUID.fromString(scenario);
          } catch (RuntimeException e) {
            throw ReplayException.badRequest(
                ReplayErrorCode.VALIDATION_ERROR,
                "scenarioRef must identify a published scenario manifest");
          }
          var source =
              manifests
                  .findById(manifestId)
                  .orElseThrow(
                      () ->
                          ReplayException.notFound(
                              ReplayErrorCode.CURATION_REQUIRED, "Scenario manifest not found"));
          ownership.requireOwnedManifest(source, ownerId);
          if (!"PUBLISHED".equals(source.getPublishState())
              || source.getGapCount() != 0
              || !"COMPLETE".equals(source.getCompleteness())
              || !"XRSPS_AUTHORITATIVE".equals(source.getFidelity())) {
            throw ReplayException.invalid(
                ReplayErrorCode.SIM_INITIAL_STATE_INCOMPLETE,
                "Session requires a complete, gap-free, published authoritative scenario");
          }
          XrspsSessionDocument doc = new XrspsSessionDocument();
          doc.lifecycle = session.snapshot();
          doc.manifest = source.getManifestJson().deepCopy();
          try (var worker = workers.open(doc)) {
            worker.checkpoint(doc);
          }
          session.markReady();
          doc.lifecycle = session.snapshot();
          return doc;
        });
  }

  public XrspsSession get(UUID id) {
    return XrspsSession.restore(repository.get(id).lifecycle);
  }

  public XrspsSession getOwned(UUID id, String ownerId) {
    return XrspsSession.restore(repository.getOwned(id, ownerId).lifecycle);
  }

  public XrspsSession transition(UUID id, Consumer<XrspsSession> change) {
    return repository.update(
        id,
        doc -> {
          XrspsSession session = XrspsSession.restore(doc.lifecycle);
          change.accept(session);
          doc.lifecycle = session.snapshot();
          return session;
        });
  }

  public XrspsSessionExecutionService.Observation observe(UUID id) {
    var doc = repository.get(id);
    var s = doc.lifecycle;
    return new XrspsSessionExecutionService.Observation(
        s.id(),
        s.mode(),
        s.status(),
        s.tick(),
        s.observationRevision(),
        s.epoch(),
        List.copyOf(doc.legalActions),
        doc.observation.deepCopy(),
        true);
  }

  public XrspsRecordedRunReport run(UUID id, List<XrspsRecordedAction> journal) {
    journal = snapshotJournal(journal);
    installJournal(id, journal);
    List<XrspsSessionReceipt> receipts = new ArrayList<>();
    for (XrspsRecordedAction action : journal) {
      XrspsSessionReceipt receipt = repository.update(id, doc -> apply(doc, action, false));
      if (receipt != null) receipts.add(receipt);
      var doc = repository.get(id);
      if (doc.mismatch != null) return report(doc, receipts, XrspsRunHaltReason.MISMATCH);
      if (receipt == null) return report(doc, receipts, XrspsRunHaltReason.SESSION_HALTED);
    }
    return report(repository.get(id), receipts, XrspsRunHaltReason.COMPLETED);
  }

  private static List<XrspsRecordedAction> snapshotJournal(List<XrspsRecordedAction> journal) {
    if (journal == null || journal.isEmpty() || journal.size() > MAX_JOURNAL_ACTIONS)
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "Journal must contain 1-1000 actions");
    return journal.stream()
        .map(
            a -> {
              if (a == null)
                throw ReplayException.badRequest(
                    ReplayErrorCode.VALIDATION_ERROR, "Journal actions cannot be null");
              return new XrspsRecordedAction(
                  a.relativeTick(),
                  a.commandId(),
                  a.actionType(),
                  a.payloadHash(),
                  a.expectedRevision(),
                  a.payload() == null ? null : a.payload().deepCopy());
            })
        .toList();
  }

  /** Finish execution of the complete pinned recording, distinct from accepting its commands. */
  public RecordingCompletion finishRecording(UUID id) {
    repository.update(
        id,
        doc -> {
          requireCompleteRecordingJournal(doc);
          return null;
        });
    while (true) {
      var result =
          repository.update(
              id,
              doc -> {
                requireCompleteRecordingJournal(doc);
                var s = XrspsSession.restore(doc.lifecycle);
                if (s.getStatus() != XrspsSessionStatus.RUNNING) return completion(doc);
                long end =
                    doc.manifest.path("recording").path("endTick").asLong()
                        - doc.manifest.path("recording").path("startTick").asLong();
                // Commit bounded segments so pause/cancel can win between segments. No viewer
                // packets
                // are sent before commit; this endpoint does not claim paced presentation.
                advance(doc, Math.min(end, s.getTick() + 100), false);
                s = XrspsSession.restore(doc.lifecycle);
                if (s.getTick() == end) {
                  s.complete("Recording executed to its end tick; task outcome is not certified");
                  doc.lifecycle = s.snapshot();
                }
                return completion(doc);
              });
      if (result.status() != XrspsSessionStatus.RUNNING) return result;
    }
  }

  private void requireCompleteRecordingJournal(XrspsSessionDocument doc) {
    var actions = doc.manifest.path("actions");
    if (!actions.isArray()
        || actions.isEmpty()
        || actions.size() != doc.journal.size()
        || doc.mismatch != null
        || doc.receipts.size() != doc.journal.size())
      throw ReplayException.conflict(
          ReplayErrorCode.ACTION_SEQUENCE_INVALID,
          "Every pinned recording action needs an accepted receipt before finishing");
    long start = doc.manifest.path("recording").path("startTick").asLong();
    for (int i = 0; i < actions.size(); i++) {
      var action = doc.journal.get(i);
      var source = actions.get(i);
      var receipt = doc.receipts.get(action.commandId());
      if (!canonical.actionDigest(source).equals(action.payloadHash())
          || receipt == null
          || receipt.outcome() != XrspsReceiptOutcome.ACCEPTED
          || (source.has("sourceActionTick")
              && source.path("sourceActionTick").asLong() - start != action.relativeTick()))
        throw ReplayException.conflict(
            ReplayErrorCode.ACTION_SEQUENCE_INVALID,
            "Recorded journal does not match the complete pinned action order and timing");
    }
  }

  private RecordingCompletion completion(XrspsSessionDocument doc) {
    var s = doc.lifecycle;
    return new RecordingCompletion(
        s.id(),
        s.status(),
        s.tick(),
        canonical.stateHash(doc.observation),
        s.status() == XrspsSessionStatus.SUCCEEDED,
        false);
  }

  public record RecordingCompletion(
      UUID sessionId,
      XrspsSessionStatus status,
      long tick,
      String domainStateHash,
      boolean recordingExecuted,
      boolean taskVerified) {}

  public XrspsRecordedRunReport step(UUID id, int ticks) {
    if (ticks < 1 || ticks > XrspsSession.MAX_STEP_TICKS)
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "Step ticks must be between 1 and 1000");
    return repository.update(
        id,
        doc -> {
          var s = XrspsSession.restore(doc.lifecycle);
          if (s.getStatus() != XrspsSessionStatus.PAUSED)
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_STATE_INVALID, "Session must be paused to step");
          if (doc.mismatch != null) return report(doc, List.of(), XrspsRunHaltReason.MISMATCH);
          long target = s.getTick() + ticks;
          List<XrspsSessionReceipt> receipts = new ArrayList<>();
          for (var action : doc.journal) {
            if (action.relativeTick() > target) break;
            var receipt = apply(doc, action, true);
            if (receipt != null) receipts.add(receipt);
            if (doc.mismatch != null) return report(doc, receipts, XrspsRunHaltReason.MISMATCH);
          }
          advance(doc, target, true);
          boolean complete = doc.receipts.size() == doc.journal.size();
          return report(
              doc,
              receipts,
              complete ? XrspsRunHaltReason.COMPLETED : XrspsRunHaltReason.SESSION_HALTED);
        });
  }

  private void installJournal(UUID id, List<XrspsRecordedAction> journal) {
    if (journal == null || journal.isEmpty() || journal.size() > MAX_JOURNAL_ACTIONS)
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "Journal must contain 1-1000 actions");
    repository.update(
        id,
        doc -> {
          long previousTick = -1;
          Set<String> identities = new java.util.HashSet<>();
          long end =
              doc.manifest.path("recording").path("endTick").asLong()
                  - doc.manifest.path("recording").path("startTick").asLong();
          for (var action : journal) {
            if (action == null
                || action.relativeTick() < previousTick
                || action.relativeTick() < 0
                || action.relativeTick() > end
                || action.expectedRevision() < 0
                || action.commandId() == null
                || !identities.add(action.commandId()))
              throw ReplayException.badRequest(
                  ReplayErrorCode.ACTION_SEQUENCE_INVALID,
                  "Journal coordinates or identities are invalid");
            try {
              UUID.fromString(action.commandId());
            } catch (RuntimeException e) {
              throw ReplayException.badRequest(
                  ReplayErrorCode.VALIDATION_ERROR, "Command identity must be a UUID");
            }
            if (action.payload() == null || !action.payload().isObject())
              throw ReplayException.invalid(
                  ReplayErrorCode.VALIDATION_ERROR,
                  "Semantic action payload is required; a hash is not executable");
            if (action.actionType() == null
                || !action.actionType().equals(action.payload().path("type").asText())
                || !doc.legalActions.contains(action.actionType()))
              throw ReplayException.invalid(
                  ReplayErrorCode.ACTION_UNSUPPORTED,
                  "Action is not supported by the pinned worker");
            if (!canonical.actionDigest(action.payload()).equals(action.payloadHash()))
              throw ReplayException.invalid(
                  ReplayErrorCode.CHECKSUM_MISMATCH,
                  "Semantic action digest does not match its payload");
            previousTick = action.relativeTick();
          }
          if (journal.size() < doc.journal.size())
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_IDEMPOTENCY_CONFLICT,
                "A committed journal cannot be shortened");
          if (doc.lifecycle.status().isTerminal() && journal.size() != doc.journal.size())
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_STATE_INVALID, "A terminal journal cannot be extended");
          for (int i = 0; i < doc.journal.size(); i++)
            if (!doc.journal.get(i).equals(journal.get(i)))
              throw ReplayException.conflict(
                  ReplayErrorCode.RUN_IDEMPOTENCY_CONFLICT,
                  "A committed journal prefix is immutable");
          doc.journal = new ArrayList<>(journal);
          return null;
        });
  }

  private XrspsSessionReceipt apply(
      XrspsSessionDocument doc, XrspsRecordedAction action, boolean paused) {
    var s = XrspsSession.restore(doc.lifecycle);
    if (doc.mismatch != null
        || s.getStatus() != (paused ? XrspsSessionStatus.PAUSED : XrspsSessionStatus.RUNNING))
      return null;
    var prior = doc.receipts.get(action.commandId());
    if (prior != null)
      return new XrspsSessionReceipt(
          prior.commandId(),
          prior.sessionId(),
          XrspsReceiptOutcome.DUPLICATE,
          prior.tick(),
          prior.revision(),
          "Committed action; no repeated effects");
    if (action.relativeTick() < s.getTick())
      return mismatch(doc, action, null, "Action is behind the session tick");
    if (action.expectedRevision() != s.getObservationRevision())
      return mismatch(
          doc,
          action,
          XrspsReceiptOutcome.REJECTED_STALE_REVISION,
          "Recorded revision does not match the session");
    advance(doc, action.relativeTick(), paused);
    s = XrspsSession.restore(doc.lifecycle);
    try (var worker = workers.open(doc)) {
      JsonNode params =
          mapper
              .createObjectNode()
              .put("expectedTick", absoluteTick(doc))
              .set("action", action.payload());
      JsonNode result;
      try {
        result = worker.call("session.apply", params);
      } catch (ReplayException e) {
        if (!SEMANTIC_ERRORS.contains(e.getErrorCode())) throw e;
        return mismatch(
            doc, action, XrspsReceiptOutcome.REJECTED_SEMANTIC_ACTION, e.getErrorCode().name());
      }
      if (!result.path("actionApplied").asBoolean(false)
          || result.path("terminalTick").asLong(-1) != absoluteTick(doc))
        throw ReplayException.invalid(
            ReplayErrorCode.XRSPS_PROTOCOL_ERROR,
            "Atomic action did not return the expected coordinates");
      doc.observation = result.path("observation");
      if (paused) s.applySteppedCommand();
      else s.applyAcceptedCommand();
      doc.lifecycle = s.snapshot();
      worker.checkpoint(doc);
      var receipt =
          new XrspsSessionReceipt(
              action.commandId(),
              s.getId(),
              XrspsReceiptOutcome.ACCEPTED,
              s.getTick(),
              s.getObservationRevision(),
              null);
      doc.receipts.put(action.commandId(), receipt);
      return receipt;
    }
  }

  private void advance(XrspsSessionDocument doc, long target, boolean paused) {
    var s = XrspsSession.restore(doc.lifecycle);
    long delta = target - s.getTick();
    if (delta == 0) return;
    long end =
        doc.manifest.path("recording").path("endTick").asLong()
            - doc.manifest.path("recording").path("startTick").asLong();
    if (delta < 0 || delta > MAX_TICK_ADVANCE || target > end)
      throw ReplayException.invalid(
          ReplayErrorCode.TICK_RANGE_INVALID,
          "Tick advance exceeds the scenario or operation bound");
    try (var worker = workers.open(doc)) {
      for (long i = 0; i < delta; i++) {
        long tick = doc.manifest.path("recording").path("startTick").asLong() + s.getTick();
        var result =
            worker.call(
                "step", mapper.createObjectNode().put("expectedTick", tick).put("maxTicks", 1));
        if (result.path("terminalTick").asLong(-1) != tick + 1)
          throw ReplayException.invalid(
              ReplayErrorCode.XRSPS_PROTOCOL_ERROR, "Worker failed to advance one tick");
        doc.observation = result.path("observation");
        if (paused) s.stepTicks(1);
        else s.advanceTicks(1);
      }
      doc.lifecycle = s.snapshot();
      worker.checkpoint(doc);
    }
  }

  private static long absoluteTick(XrspsSessionDocument doc) {
    return doc.manifest.path("recording").path("startTick").asLong() + doc.lifecycle.tick();
  }

  private static XrspsSessionReceipt mismatch(
      XrspsSessionDocument doc,
      XrspsRecordedAction action,
      XrspsReceiptOutcome outcome,
      String detail) {
    var s = doc.lifecycle;
    doc.mismatch =
        new XrspsActionMismatch(
            s.id(),
            action.commandId(),
            action.relativeTick(),
            action.expectedRevision(),
            s.tick(),
            s.observationRevision(),
            outcome,
            detail);
    var failed = XrspsSession.restore(doc.lifecycle);
    failed.fail("Recorded action mismatch: " + detail);
    doc.lifecycle = failed.snapshot();
    return outcome == null
        ? null
        : new XrspsSessionReceipt(
            action.commandId(), s.id(), outcome, s.tick(), s.observationRevision(), detail);
  }

  private static XrspsRecordedRunReport report(
      XrspsSessionDocument doc, List<XrspsSessionReceipt> receipts, XrspsRunHaltReason reason) {
    var s = doc.lifecycle;
    return new XrspsRecordedRunReport(
        s.id(),
        receipts.size(),
        List.copyOf(receipts),
        s.tick(),
        s.observationRevision(),
        doc.mismatch,
        reason);
  }
}
