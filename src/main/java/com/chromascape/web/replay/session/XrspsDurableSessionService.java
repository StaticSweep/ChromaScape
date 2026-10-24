package com.chromascape.web.replay.session;

import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.manifest.repository.ScenarioManifestRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.chromascape.web.replay.worker.ReplaySourceSchedule;
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
 * executable. Client presentation and configured AI consume this same committed world.
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
    if (mode != XrspsSessionMode.RECORDED_ACTIONS && mode != XrspsSessionMode.AI_TASK)
      throw ReplayException.invalid(
          ReplayErrorCode.ACTION_UNSUPPORTED, "Unsupported session execution mode");
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
          if (source.getManifestVersion() == 2) {
            com.chromascape.web.replay.manifest.ScenarioManifestImportValidator
                .requireSimulationAllowed(source.getManifestJson());
          }
          doc.manifest = source.getManifestJson().deepCopy();
          try (var worker = workers.open(doc)) {
            worker.checkpoint(doc);
          }
          session.markReady();
          doc.lifecycle = session.snapshot();
          return doc;
        });
  }

  /** Bind a paced recording once; its source journal remains immutable. */
  public void bindPlayback(UUID id, List<XrspsRecordedAction> journal, String owner) {
    if (get(id).getMode() != XrspsSessionMode.RECORDED_ACTIONS)
      throw ReplayException.invalid(
          ReplayErrorCode.ACTION_UNSUPPORTED, "Recording playback requires recorded mode");
    installJournal(id, snapshotJournal(journal));
    repository.update(
        id,
        doc -> {
          requirePinnedJournal(doc, false);
          if (doc.playbackOwner != null
              && !owner.equals(doc.playbackOwner)
              && doc.lifecycle.leaseId().equals(doc.playbackLease))
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_STATE_INVALID,
                "Another playback coordinator owns this lease; recover before replacing it");
          doc.playbackOwner = owner;
          doc.playbackLease = doc.lifecycle.leaseId();
          if (doc.lifecycle.status() == XrspsSessionStatus.READY) {
            var session = XrspsSession.restore(doc.lifecycle);
            session.start();
            doc.lifecycle = session.snapshot();
          }
          return null;
        });
  }

  /** One committed tick at most. Viewer speed never calls or changes this clock. */
  public XrspsSessionStatus playbackTick(UUID id, String lease, String owner) {
    return repository.update(
        id,
        doc -> {
          var session = XrspsSession.restore(doc.lifecycle);
          if (!session.getLeaseId().equals(lease)
              || !owner.equals(doc.playbackOwner)
              || !lease.equals(doc.playbackLease))
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_STATE_INVALID, "Playback lease was fenced");
          if (session.getMode() != XrspsSessionMode.RECORDED_ACTIONS
              || session.getStatus() != XrspsSessionStatus.RUNNING) return session.getStatus();
          requirePinnedJournal(doc, false);
          long tick = session.getTick();
          for (var action : doc.journal) {
            if (action.relativeTick() > tick) break;
            if (!doc.receipts.containsKey(action.commandId())) apply(doc, action, false);
            if (doc.mismatch != null) return doc.lifecycle.status();
          }
          long end =
              doc.manifest.path("recording").path("endTick").asLong()
                  - doc.manifest.path("recording").path("startTick").asLong();
          if (tick < end) advance(doc, tick + 1, false);
          else if (doc.receipts.size() == doc.journal.size()) {
            requireCompleteRecordingJournal(doc);
            session = XrspsSession.restore(doc.lifecycle);
            session.complete("Pinned recording executed; task outcome verification is separate");
            doc.lifecycle = session.snapshot();
          }
          return doc.lifecycle.status();
        });
  }

  /** Reconstruct only an archived, proven recorded state and fence the previous execution epoch. */
  public XrspsSession seek(UUID id, long targetTick) {
    return repository.update(
        id,
        doc -> {
          var old = doc.lifecycle;
          if (old.mode() != XrspsSessionMode.RECORDED_ACTIONS
              || doc.mismatch != null
              || (old.status() != XrspsSessionStatus.PAUSED
                  && old.status() != XrspsSessionStatus.SUCCEEDED))
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_STATE_INVALID,
                "Pause recorded playback before seeking; mismatched runs require a new run");
          if (targetTick < 0 || targetTick > MAX_TICK_ADVANCE)
            throw ReplayException.badRequest(
                ReplayErrorCode.TICK_RANGE_INVALID, "Seek is outside the bounded restore window");
          long revision =
              doc.verifiedStates.keySet().stream()
                  .filter(k -> k.startsWith(targetTick + ":"))
                  .mapToLong(k -> Long.parseLong(k.substring(k.indexOf(':') + 1)))
                  .max()
                  .orElse(-1);
          if (revision < 0)
            throw ReplayException.conflict(
                ReplayErrorCode.SNAPSHOT_INCOMPATIBLE,
                "No archived domain hash exists at this tick");
          String expected = doc.verifiedStates.get(targetTick + ":" + revision);
          var restored = new XrspsSessionDocument();
          restored.manifest = doc.manifest.deepCopy();
          restored.journal = snapshotJournal(doc.journal);
          restored.lifecycle =
              new XrspsSession.Snapshot(
                  old.id(),
                  old.mode(),
                  old.idempotencyKey(),
                  old.scenarioRef(),
                  old.ownerId(),
                  XrspsSessionStatus.PAUSED,
                  0,
                  0,
                  old.epoch() + 1,
                  UUID.randomUUID().toString(),
                  null,
                  java.time.Instant.now());
          try (var worker = workers.open(restored)) {
            worker.checkpoint(restored);
          }
          for (var action : restored.journal) {
            if (action.relativeTick() > targetTick || action.expectedRevision() >= revision) break;
            apply(restored, action, true);
            if (restored.mismatch != null)
              throw ReplayException.conflict(
                  ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, "Recorded prefix could not be restored");
          }
          advance(restored, targetTick, true);
          if (restored.lifecycle.observationRevision() != revision
              || !expected.equals(canonical.stateHash(restored.observation)))
            throw ReplayException.conflict(
                ReplayErrorCode.SNAPSHOT_INCOMPATIBLE,
                "Reconstructed domain hash differs from the archived run");
          if (doc.seekHistory.size() >= 32)
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_STATE_INVALID,
                "Seek history budget exhausted; create a new session");
          var archive = mapper.createObjectNode();
          archive.set("lifecycle", mapper.valueToTree(old));
          archive.set("receipts", mapper.valueToTree(doc.receipts));
          archive.put("domainStateHash", canonical.stateHash(doc.observation));
          doc.seekHistory.add(archive);
          doc.playbackOwner = null;
          doc.playbackLease = null;
          doc.retiredLeases.add(old.leaseId());
          doc.lifecycle = restored.lifecycle;
          doc.checkpoint = restored.checkpoint;
          doc.observation = restored.observation;
          doc.receipts = restored.receipts;
          doc.clientBootstrap = restored.clientBootstrap;
          doc.clientFrames = restored.clientFrames;
          doc.clientSequence = restored.clientSequence;
          return XrspsSession.restore(doc.lifecycle);
        });
  }

  /**
   * Re-run committed AI action bytes through a private production world without another model call.
   */
  public String verifyAiJournal(XrspsSessionDocument doc) {
    var replay = new XrspsSessionDocument();
    replay.manifest = doc.manifest.deepCopy();
    var old = doc.lifecycle;
    replay.lifecycle =
        new XrspsSession.Snapshot(
            old.id(),
            old.mode(),
            old.idempotencyKey(),
            old.scenarioRef(),
            old.ownerId(),
            XrspsSessionStatus.RUNNING,
            0,
            0,
            old.epoch(),
            old.leaseId(),
            null,
            java.time.Instant.now());
    try (var worker = workers.open(replay)) {
      worker.checkpoint(replay);
    }
    for (var action : doc.journal) {
      if (apply(replay, action, false) == null || replay.mismatch != null)
        throw ReplayException.conflict(
            ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, "AI journal replay failed");
    }
    advance(replay, old.tick(), false);
    String actual = canonical.stateHash(replay.observation);
    if (!actual.equals(canonical.stateHash(doc.observation)))
      throw ReplayException.conflict(
          ReplayErrorCode.SNAPSHOT_INCOMPATIBLE, "AI journal replay domain hash differs");
    return actual;
  }

  public XrspsSession recover(UUID id, String fencedLease) {
    return repository.update(
        id,
        doc -> {
          var session = XrspsSession.restore(doc.lifecycle);
          boolean ai = session.getMode() == XrspsSessionMode.AI_TASK;
          if (ai
              && (doc.aiDecision == null
                  || !doc.aiDecision.isObject()
                  || doc.aiTask == null
                  || !doc.aiTask.isObject()
                  || doc.aiRecoveryCount >= 3
                  || java.time.Instant.now()
                      .isAfter(java.time.Instant.parse(doc.aiTask.path("deadline").asText()))))
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_STATE_INVALID,
                "AI recovery requires a committed decision and remaining task/recovery budget");
          if (!session.getLeaseId().equals(fencedLease))
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_STATE_INVALID, "Recovery lease is stale");
          if (session.getStatus() == XrspsSessionStatus.RUNNING
              || session.getStatus() == XrspsSessionStatus.PAUSED) session.reportWorkerLoss();
          String expected = canonical.stateHash(doc.observation);
          try (var worker = workers.open(doc)) {
            if (!expected.equals(canonical.stateHash(doc.observation)))
              throw ReplayException.conflict(
                  ReplayErrorCode.SNAPSHOT_INCOMPATIBLE,
                  "Recovered checkpoint domain hash differs");
            worker.checkpoint(doc);
          }
          String newLease = UUID.randomUUID().toString();
          if (doc.retiredLeases.contains(newLease))
            throw ReplayException.conflict(
                ReplayErrorCode.RUN_STATE_INVALID, "Lease identity already retired");
          session.recoverFromCheckpoint(fencedLease, newLease);
          doc.retiredLeases.add(fencedLease);
          doc.playbackOwner = null;
          doc.playbackLease = null;
          doc.lifecycle = session.snapshot();
          if (ai) {
            doc.aiRecoveryCount++;
            ((com.fasterxml.jackson.databind.node.ObjectNode) doc.aiTask)
                .put("lease", newLease)
                .put("epoch", session.getEpoch());
          }
          return session;
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
    if (get(id).getMode() != XrspsSessionMode.RECORDED_ACTIONS)
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_STATE_INVALID,
          "AI actions require the configured decision coordinator");
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
    if (doc.lifecycle.mode() != XrspsSessionMode.RECORDED_ACTIONS)
      throw ReplayException.conflict(
          ReplayErrorCode.RUN_STATE_INVALID, "Only recorded replay can finish a recording");
    requirePinnedJournal(doc, true);
  }

  private void requirePinnedJournal(XrspsSessionDocument doc, boolean requireReceipts) {
    var actions = doc.manifest.path("actions");
    if (!actions.isArray()
        || actions.isEmpty()
        || actions.size() != doc.journal.size()
        || doc.mismatch != null
        || (requireReceipts && doc.receipts.size() != doc.journal.size()))
      throw ReplayException.conflict(
          ReplayErrorCode.ACTION_SEQUENCE_INVALID,
          "Every pinned recording action needs an accepted receipt before finishing");
    long start = doc.manifest.path("recording").path("startTick").asLong();
    for (int i = 0; i < actions.size(); i++) {
      var action = doc.journal.get(i);
      var source = actions.get(i);
      var receipt = doc.receipts.get(action.commandId());
      if (!canonical
              .actionDigest(ReplaySourceSchedule.semanticAction(source))
              .equals(action.payloadHash())
          || (requireReceipts
              && (receipt == null || receipt.outcome() != XrspsReceiptOutcome.ACCEPTED))
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
          if (s.getMode() != XrspsSessionMode.RECORDED_ACTIONS
              || s.getStatus() != XrspsSessionStatus.PAUSED)
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
          boolean timedRecording = timedRecording(doc);
          int sourceIndex = 0;
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
            if (timedRecording) {
              JsonNode source = doc.manifest.path("actions").path(sourceIndex);
              long start = doc.manifest.path("recording").path("startTick").asLong();
              if (!source.isObject()
                  || source.path("sourceActionTick").asLong() - start != action.relativeTick()
                  || !canonical
                      .actionDigest(ReplaySourceSchedule.semanticAction(source))
                      .equals(action.payloadHash()))
                throw ReplayException.invalid(
                    ReplayErrorCode.ACTION_SEQUENCE_INVALID,
                    "Journal prefix must preserve each exact indexed source action and tick");
            }
            sourceIndex++;
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

  XrspsSessionReceipt apply(XrspsSessionDocument doc, XrspsRecordedAction action, boolean paused) {
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
      var params = mapper.createObjectNode().put("expectedTick", absoluteTick(doc));
      params.set("action", action.payload());
      if (timedRecording(doc)) {
        int index = doc.journal.indexOf(action);
        if (index < 0)
          throw ReplayException.invalid(
              ReplayErrorCode.ACTION_SEQUENCE_INVALID,
              "Source command is absent from the installed journal");
        params.put("sourceActionIndex", index);
      }
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

  void advance(XrspsSessionDocument doc, long target, boolean paused) {
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
    try (var worker = paused ? workers.open(doc) : workers.openPaced(doc)) {
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

  private static boolean timedRecording(XrspsSessionDocument doc) {
    return doc.lifecycle.mode() == XrspsSessionMode.RECORDED_ACTIONS
        && new ReplaySourceSchedule(doc.manifest).timed();
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
