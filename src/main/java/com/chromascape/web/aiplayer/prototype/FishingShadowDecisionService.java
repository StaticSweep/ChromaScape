package com.chromascape.web.aiplayer.prototype;

import com.chromascape.web.aiplayer.prototype.FishingDemonstrationPolicy.Prediction;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Evaluates each ordered live Micro6 tick against the active validated fishing model and produces a
 * shadow decision or an explicit abstention.
 *
 * <p>The service is observation-only. It never dispatches Agent Server, RuneLite, mouse, or
 * keyboard input and never transitions itself into control mode. It runs only when {@code
 * enabled=true}, {@code shadow-mode=true}, and {@code control-enabled=false}. Decisions are handed
 * to the nonblocking {@link FishingShadowDecisionWriter}; disk I/O never blocks tick ingestion.
 *
 * <p>Active shadow state is keyed by both the debug session id and the shadow run id ({@link
 * ShadowRunKey}), so reusing a textual session id across Micro6 restarts produces fully isolated
 * in-memory state, persisted history, and run discovery. Each run is opened explicitly via {@link
 * #openSession} during {@code SESSION_START} before any tick arrives.
 *
 * <p>Finalization and publication are generation-aware and ordered against {@link
 * #cleanupSession(String, String)}. An evaluation resolves a {@link SessionState}, captures its
 * generation, and then performs the extraction/policy flow while holding that state's lock.
 * Immediately before finalization it re-verifies the state is not closed, the map still contains
 * the same state instance, and the generation still matches. Counters, persistence enqueue, and the
 * publisher are all executed while the state lock is held, so one of two outcomes is guaranteed:
 * the decision finalizes and publishes before cleanup runs, or cleanup closes the state first and
 * the evaluation can neither finalize nor broadcast. Cleanup with an old {@code shadowRunId} can
 * never close a newer run sharing the textual session id.
 */
@Service
public class FishingShadowDecisionService {

  private static final Logger log = LoggerFactory.getLogger(FishingShadowDecisionService.class);
  private static final int SCHEMA_VERSION = 2;
  private static final String TYPE = "AI_PLAYER_SHADOW_DECISION";
  private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
  private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
  private static final int DEFAULT_HISTORY_LIMIT = 50;
  private static final int MAX_HISTORY_LIMIT = 500;

  private final FishingActiveModelService activeModelService;
  private final ObjectMapper objectMapper;
  private final FishingShadowDecisionWriter writer;

  @Value("${ai-player.dataset.output-dir:.chromascape/datasets}")
  private String outputDir;

  @Value("${features.ai-player.enabled:false}")
  private boolean enabled;

  @Value("${features.ai-player.shadow-mode:false}")
  private boolean shadowMode;

  @Value("${features.ai-player.control-enabled:false}")
  private boolean controlEnabled;

  private final Map<ShadowRunKey, SessionState> sessions = new ConcurrentHashMap<>();
  private static final java.util.concurrent.atomic.AtomicLong SESSION_GENERATION =
      new java.util.concurrent.atomic.AtomicLong();

  /** Identity of one shadow run: a debug session id combined with a run id. */
  record ShadowRunKey(String sessionId, String shadowRunId) {}

  /**
   * Per-run in-memory inference state. Mutations are guarded by this instance's monitor.
   *
   * <p>{@code closed} is set by {@link #cleanupSession} under the state lock before the state is
   * removed from {@code sessions}; an evaluation that resolved this exact state before cleanup must
   * refuse to finalize once {@code closed} is true. {@code generation} gives each state instance a
   * unique identity so a later, genuine new run with the same textual session id can be
   * distinguished from the closed generation it replaced.
   */
  private static final class SessionState {
    final long generation = SESSION_GENERATION.incrementAndGet();
    boolean closed;
    boolean baselineEstablished;
    int initialInventoryTotal;
    long latestTick = -1;
    FishingShadowDecision latest;
    int processed;
    int predicted;
    int abstained;
  }

  /**
   * Internal generation-aware evaluation result. {@code sessionGeneration} is used only for
   * finalization validation and is never persisted or broadcast.
   */
  private record FishingShadowEvaluation(
      FishingShadowDecision decision, ShadowRunKey key, long sessionGeneration) {}

  /** Parsed stats for one persisted run file. */
  private record RunFileStats(
      long firstTick, long lastTick, String firstObservedAt, String lastObservedAt, int count) {}

  public FishingShadowDecisionService(
      FishingActiveModelService activeModelService,
      ObjectMapper objectMapper,
      FishingShadowDecisionWriter writer) {
    this.activeModelService = activeModelService;
    this.objectMapper = objectMapper;
    this.writer = writer;
  }

  /**
   * Opens one shadow run by creating its empty in-memory state before any tick arrives.
   *
   * <p>Both identifiers are validated. A conflicting duplicate open for the same key is rejected
   * (the existing state is retained). The baseline stays unset and counters start at zero until the
   * first valid tick.
   *
   * @param sessionId the canonical debug session id
   * @param shadowRunId the collision-resistant shadow run id
   * @return true when a fresh run state was opened; false when identifiers were invalid or the key
   *     was already open
   */
  public boolean openSession(String sessionId, String shadowRunId) {
    if (!isSafeSessionId(sessionId) || !isSafeRunId(shadowRunId)) {
      log.warn(
          "Rejecting shadow run open with invalid identifiers: session={}, run={}",
          safeDescription(sessionId),
          safeDescription(shadowRunId));
      return false;
    }
    ShadowRunKey key = new ShadowRunKey(sessionId, shadowRunId);
    SessionState existing = sessions.putIfAbsent(key, new SessionState());
    if (existing != null) {
      log.warn("Rejecting conflicting duplicate shadow run open for key: {}", key);
      return false;
    }
    log.info("Opened fishing shadow run: session={}, run={}", sessionId, shadowRunId);
    return true;
  }

  /**
   * Evaluates one live tick and, when a decision is produced, finalizes counters, enqueues
   * persistence, and invokes the supplied publisher while the session lock is held.
   *
   * <p>The publisher must be fast (serialize and send the debugger message only) and must not
   * perform disk access or AI inference; it is invoked under the session lock so ordering against
   * cleanup is established. If the publisher throws, the publication failure is logged and the
   * finalized decision and persistence enqueue are preserved.
   *
   * <p>No decision is produced (and nothing is finalized or published) when the service is not
   * operational, the session or run id is unsafe, the run was never opened or already closed, the
   * tick is stale/duplicate, or a concurrent cleanup closed the run.
   *
   * @param batch the ordered live tick batch
   * @param shadowRunId the shadow run id bound to the owning connection
   * @param publisher invoked with the finalized decision while the session lock is held
   */
  public void evaluateAndPublish(
      TickBatchMessage batch, String shadowRunId, Consumer<FishingShadowDecision> publisher) {
    if (!enabled || !shadowMode || controlEnabled) {
      return;
    }
    long startNanos = System.nanoTime();
    if (batch == null) {
      return;
    }
    String sessionId = batch.getSessionId();
    if (!isSafeSessionId(sessionId)) {
      log.warn("Rejecting shadow tick with invalid session id: {}", safeDescription(sessionId));
      return;
    }
    if (!isSafeRunId(shadowRunId)) {
      log.warn(
          "Rejecting shadow tick with invalid run id: session={}, run={}",
          safeDescription(sessionId),
          safeDescription(shadowRunId));
      return;
    }
    ShadowRunKey key = new ShadowRunKey(sessionId, shadowRunId);
    SessionState state = sessions.get(key);
    if (state == null) {
      log.warn("Rejecting shadow tick for unopened or closed run: {}", key);
      return;
    }
    long generation = state.generation;
    synchronized (state) {
      if (state.closed) {
        return;
      }
      if (sessions.get(key) != state) {
        return;
      }
      if (batch.getTick() <= state.latestTick) {
        return;
      }

      FishingShadowDecision decision =
          computeDecision(state, sessionId, shadowRunId, batch, startNanos);
      finalizeAndPublishLocked(
          state, new FishingShadowEvaluation(decision, key, generation), publisher);
    }
  }

  /**
   * Computes the shadow decision for a valid tick. Caller must hold the session lock and have
   * already rejected closed/stale sessions, so this always produces a decision.
   */
  private FishingShadowDecision computeDecision(
      SessionState state,
      String sessionId,
      String shadowRunId,
      TickBatchMessage batch,
      long startNanos) {
    if (!hasRequiredState(batch)) {
      return abstain(
          sessionId, shadowRunId, batch, "ABSTAIN_INVALID_STATE", null, null, startNanos);
    }

    // The inventory baseline is established on the first valid session tick even when no model is
    // active yet, so fish already caught during a pre-activation interval are never treated as
    // starting inventory once a model becomes active.
    if (!state.baselineEstablished) {
      state.initialInventoryTotal = FishingStateExtractor.inventoryTotal(batch);
      state.baselineEstablished = true;
    }

    FishingModel model = activeModelService.getActiveModel();
    if (model == null) {
      return abstain(
          sessionId, shadowRunId, batch, "ABSTAIN_NO_ACTIVE_MODEL", null, null, startNanos);
    }
    String modelId = model.modelId();

    FishingState fishingState = FishingStateExtractor.extract(batch, state.initialInventoryTotal);

    if (fishingState.player().moving()) {
      return abstain(sessionId, shadowRunId, batch, "ABSTAIN_MOVING", null, modelId, startNanos);
    }
    if (!fishingState.player().idle() || fishingState.player().animationId() != -1) {
      return abstain(sessionId, shadowRunId, batch, "ABSTAIN_BUSY", null, modelId, startNanos);
    }

    Prediction prediction =
        FishingDemonstrationPolicy.predict(
            fishingState, model.examples(), model.maxAcceptedPositionDistance());
    if (prediction.isAbstain()) {
      return abstain(
          sessionId,
          shadowRunId,
          batch,
          prediction.abstentionReason(),
          prediction.policyDistance(),
          modelId,
          startNanos);
    }

    if (!isValidFishingAction(prediction, fishingState)) {
      return abstain(
          sessionId,
          shadowRunId,
          batch,
          "ABSTAIN_UNSUPPORTED_ACTION",
          prediction.policyDistance(),
          modelId,
          startNanos);
    }

    return new FishingShadowDecision(
        SCHEMA_VERSION,
        TYPE,
        "PREDICTED",
        modelId,
        sessionId,
        shadowRunId,
        batch.getTick(),
        observedAt(batch.getTimestamp()),
        prediction.action(),
        prediction.targetNpc(),
        null,
        prediction.policyDistance(),
        prediction.matchedExampleTick(),
        latencyMicros(startNanos));
  }

  /**
   * Finalizes counters/latest decision, enqueues persistence, and invokes the publisher. Caller
   * must hold the session lock.
   *
   * <p>Immediately before finalization the evaluation is validated against the current run state:
   * the state must not be closed, the map must still contain this exact state instance, and the
   * generation must still match. This guarantees a cleanup that closed the run before this point
   * can never be followed by a late counter update, persistence enqueue, or broadcast.
   */
  private void finalizeAndPublishLocked(
      SessionState state,
      FishingShadowEvaluation evaluation,
      Consumer<FishingShadowDecision> publisher) {
    if (state.closed) {
      return;
    }
    if (sessions.get(evaluation.key()) != state) {
      return;
    }
    if (state.generation != evaluation.sessionGeneration()) {
      return;
    }
    FishingShadowDecision decision = evaluation.decision();
    state.latestTick = decision.tick();
    state.processed++;
    if ("PREDICTED".equals(decision.status())) {
      state.predicted++;
    } else {
      state.abstained++;
    }
    state.latest = decision;
    writer.enqueue(decision);
    if (publisher != null) {
      try {
        publisher.accept(decision);
      } catch (Exception e) {
        log.error(
            "Failed to publish shadow decision for session {} tick {}: {}",
            decision.sessionId(),
            decision.tick(),
            e.getMessage(),
            e);
      }
    }
  }

  /**
   * Closes and removes one shadow run's in-memory state on explicit stop, disconnect, or transport
   * finalization.
   *
   * <p>Cleanup atomically marks the resolved state closed and removes it from {@code sessions}
   * under the state lock. An evaluation that resolved this exact state before cleanup may finish
   * computation, but once {@code closed} is set it can no longer finalize (no counters, no
   * persistence enqueue, no broadcastable decision), and this method returns only after any
   * publication holding that state lock completes. Repeated cleanup of the same run is idempotent.
   * Cleanup with an old {@code shadowRunId} never closes a newer run sharing the textual session
   * id, and a later genuine new run creates a fresh state generation with a reset baseline and zero
   * counters.
   */
  public void cleanupSession(String sessionId, String shadowRunId) {
    if (sessionId == null || shadowRunId == null) {
      return;
    }
    ShadowRunKey key = new ShadowRunKey(sessionId, shadowRunId);
    SessionState state = sessions.get(key);
    if (state == null) {
      return;
    }
    synchronized (state) {
      state.closed = true;
      sessions.remove(key, state);
    }
    log.info("Cleared fishing shadow run state: {}", key);
  }

  /**
   * Returns the effective feature flags, global persistence status, and per-run status.
   *
   * <p>When both {@code sessionId} and {@code shadowRunId} are provided, session fields are
   * returned only for that exact active run; otherwise they remain zero with no latest decision.
   * After resolving a state and acquiring its lock, the state is revalidated ({@code !closed} and
   * the map still holds this exact state); when validation fails the empty session defaults are
   * retained. No "latest session" or "latest run" is ever inferred.
   */
  public Map<String, Object> status(String sessionId, String shadowRunId) {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("enabled", enabled);
    status.put("shadowMode", shadowMode);
    status.put("controlEnabled", false);
    status.put("controlRequested", controlEnabled);
    status.put("controlAllowed", false);
    boolean operational = enabled && shadowMode && !controlEnabled;
    status.put("operational", operational);

    // Model, model id, summary, and activation state/error all come from one locked snapshot so an
    // activation change between reads can never produce contradictory status fields.
    FishingActiveModelService.ActiveModelInfo activeInfo = activeModelService.getActiveSnapshot();
    boolean ready = operational && activeInfo != null && activeInfo.model() != null;
    status.put("readyForInference", ready);
    status.put("inactiveReason", resolveInactiveReason(operational, activeInfo));
    status.put("activationState", activeInfo != null ? activeInfo.activationState() : null);
    status.put("activationLoadError", activeInfo != null ? activeInfo.activationLoadError() : null);
    status.put("activeModelId", activeInfo != null ? activeInfo.modelId() : null);
    status.put("activeModel", activeInfo != null ? activeInfo.summary() : null);
    status.put("sessionId", null);
    status.put("shadowRunId", null);
    status.put("latestTick", -1L);
    status.put("latestDecision", null);
    status.put("processed", 0);
    status.put("predicted", 0);
    status.put("abstained", 0);

    // Writer lifecycle, queue, and counters all come from one immutable snapshot so a status
    // consumer can never observe a mixed picture of the persistence pipeline.
    FishingShadowDecisionWriter.WriterStatusSnapshot writerSnapshot = writer.statusSnapshot();
    status.put("persistenceLifecycle", writerSnapshot.lifecycle());
    status.put("persistenceQueueSize", writerSnapshot.queueSize());
    status.put("persistenceEnqueued", writerSnapshot.enqueued());
    status.put("persistenceQueued", writerSnapshot.queued());
    status.put("persistenceInFlight", writerSnapshot.inFlight());
    status.put("persistenceCompleted", writerSnapshot.completed());
    status.put("persistenceWritten", writerSnapshot.written());
    status.put("persistenceFailed", writerSnapshot.failed());
    status.put("persistenceAbandoned", writerSnapshot.abandoned());
    status.put("persistenceDropped", writerSnapshot.dropped());
    status.put("persistenceError", writerSnapshot.error());

    if (sessionId != null && shadowRunId != null) {
      ShadowRunKey key = new ShadowRunKey(sessionId, shadowRunId);
      SessionState state = sessions.get(key);
      if (state != null) {
        synchronized (state) {
          // Revalidate under the lock: the run must still be active and the map must still hold
          // this exact state, otherwise a cleanup race retains the empty session defaults.
          if (!state.closed && sessions.get(key) == state) {
            status.put("sessionId", sessionId);
            status.put("shadowRunId", shadowRunId);
            status.put("latestTick", state.latestTick);
            status.put("latestDecision", state.latest);
            status.put("processed", state.processed);
            status.put("predicted", state.predicted);
            status.put("abstained", state.abstained);
          }
        }
      }
    }
    return status;
  }

  /**
   * Explains why the shadow agent is not ready. When the feature flags are operational but no model
   * is active, the activation state/error is surfaced so the debugger can distinguish a missing
   * selection, corrupt activation state, missing model, or failed replay validation.
   */
  private String resolveInactiveReason(
      boolean operational, FishingActiveModelService.ActiveModelInfo activeInfo) {
    if (!operational) {
      return inactiveReason();
    }
    if (activeInfo == null || activeInfo.model() == null) {
      return activeInfo != null && activeInfo.activationState() != null
          ? activeInfo.activationState()
          : "NO_ACTIVE_MODEL";
    }
    return null;
  }

  /**
   * Discovers the persisted shadow runs for a session, newest run first.
   *
   * <p>Schema-v2 run files live at {@code {sessionId}/{shadowRunId}.jsonl}; a legacy schema-v1 file
   * at {@code {sessionId}.jsonl} appears as one summary with {@code shadowRunId} null and {@code
   * legacy} true. Active in-memory runs are merged with their on-disk stats.
   *
   * @param sessionId the sanitized session id
   * @return a map with {@code sessionId} and a {@code runs} list
   */
  public Map<String, Object> runs(String sessionId) {
    if (!isSafeSessionId(sessionId)) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST, "INVALID_SESSION_ID", "sessionId contains invalid characters");
    }
    List<Map<String, Object>> runList = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    // Active in-memory runs, with their on-disk stats merged in.
    for (Map.Entry<ShadowRunKey, SessionState> entry : sessions.entrySet()) {
      ShadowRunKey key = entry.getKey();
      if (!key.sessionId().equals(sessionId)) {
        continue;
      }
      SessionState state = entry.getValue();
      boolean active;
      synchronized (state) {
        active = !state.closed && sessions.get(key) == state;
      }
      runList.add(summarizeRun(sessionId, key.shadowRunId(), state, active, false));
      seen.add(key.shadowRunId());
    }

    // Historical schema-v2 run files.
    Path dir = runDirectory(sessionId);
    if (Files.isDirectory(dir)) {
      try (java.util.stream.Stream<Path> files = Files.list(dir)) {
        for (Path file :
            files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
          String name = file.getFileName().toString();
          String runId = name.substring(0, name.length() - ".jsonl".length());
          if (!seen.add(runId)) {
            continue;
          }
          runList.add(summarizeRun(sessionId, runId, null, false, false));
        }
      } catch (IOException e) {
        log.warn("Failed to list shadow run files for session {}: {}", sessionId, e.getMessage());
      }
    }

    // Legacy schema-v1 file.
    Path legacy = legacyShadowFile(sessionId);
    if (Files.exists(legacy)) {
      runList.add(summarizeRun(sessionId, null, null, false, true));
    }

    // Newest run first: by last observed time descending, then by run id descending.
    runList.sort(
        Comparator.comparing(
                (Map<String, Object> run) -> (String) run.get("lastObservedAt"),
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(
                (Map<String, Object> run) -> (String) run.get("shadowRunId"),
                Comparator.nullsLast(Comparator.reverseOrder())));
    return Map.of("sessionId", sessionId, "runs", runList);
  }

  /** Builds one run summary from in-memory state and on-disk stats. */
  private Map<String, Object> summarizeRun(
      String sessionId, String runId, SessionState state, boolean active, boolean legacy) {
    Path file =
        legacy
            ? legacyShadowFile(sessionId)
            : runId != null ? runShadowFile(sessionId, runId) : null;
    RunFileStats stats = file != null ? statsOf(file) : null;
    Long firstTick = null;
    Long lastTick = null;
    String firstObservedAt = null;
    String lastObservedAt = null;
    int decisionCount = 0;
    if (state != null) {
      synchronized (state) {
        if (!state.closed && sessions.get(new ShadowRunKey(sessionId, runId)) == state) {
          active = true;
        }
        decisionCount = state.processed;
        if (state.latest != null) {
          lastObservedAt = state.latest.observedAt();
          lastTick = state.latestTick;
          if (firstObservedAt == null) {
            firstObservedAt = state.latest.observedAt();
          }
        } else if (state.latestTick >= 0) {
          lastTick = state.latestTick;
        }
      }
    }
    if (stats != null) {
      if (firstTick == null) {
        firstTick = stats.firstTick();
      } else {
        firstTick = Math.min(firstTick, stats.firstTick());
      }
      if (lastTick == null) {
        lastTick = stats.lastTick();
      } else {
        lastTick = Math.max(lastTick, stats.lastTick());
      }
      if (firstObservedAt == null) {
        firstObservedAt = stats.firstObservedAt();
      }
      lastObservedAt = stats.lastObservedAt();
      decisionCount = Math.max(decisionCount, stats.count());
    }
    return Map.of(
        "shadowRunId", runId,
        "firstObservedAt", firstObservedAt,
        "lastObservedAt", lastObservedAt,
        "firstTick", firstTick != null ? firstTick : -1L,
        "lastTick", lastTick != null ? lastTick : -1L,
        "decisionCount", decisionCount,
        "active", active,
        "legacy", legacy);
  }

  /** Parses one run file into tick/time/count stats, tolerating an incomplete trailing line. */
  private RunFileStats statsOf(Path file) {
    long firstTick = Long.MAX_VALUE;
    long lastTick = -1;
    String firstObservedAt = null;
    String lastObservedAt = null;
    int count = 0;
    ReentrantReadWriteLock.ReadLock readLock = writer.readLock();
    readLock.lock();
    try {
      String content = Files.readString(file);
      for (String line : content.split("\n", -1)) {
        String cleaned = stripCarriageReturn(line);
        if (cleaned.isBlank()) {
          continue;
        }
        try {
          FishingShadowDecision decision =
              objectMapper.readValue(cleaned, FishingShadowDecision.class);
          count++;
          if (decision.tick() < firstTick) {
            firstTick = decision.tick();
          }
          if (decision.tick() > lastTick) {
            lastTick = decision.tick();
          }
          if (firstObservedAt == null) {
            firstObservedAt = decision.observedAt();
          }
          lastObservedAt = decision.observedAt();
        } catch (IOException e) {
          log.debug("Tolerating unparsable shadow history line in {}: {}", file, e.getMessage());
        }
      }
    } catch (IOException e) {
      log.warn("Failed to read shadow run file {}: {}", file, e.getMessage());
    } finally {
      readLock.unlock();
    }
    if (count == 0) {
      return null;
    }
    return new RunFileStats(firstTick, lastTick, firstObservedAt, lastObservedAt, count);
  }

  /**
   * Reads persisted shadow history for one run (or the legacy file), newest first.
   *
   * <p>Reads take the writer's read lock so a concurrent append can never expose a partially
   * written final line. A lone incomplete trailing line (e.g. from a crash mid-append) is
   * tolerated, while a malformed completed/middle line is treated as corruption and fails the read.
   * Legacy and schema-v2 history are never merged.
   *
   * @param sessionId the sanitized session id
   * @param shadowRunId the run id (required unless {@code legacy})
   * @param legacy whether to read the legacy schema-v1 file instead
   * @param limit requested result count (clamped to 1..500, default 50)
   * @return the decisions
   */
  public List<FishingShadowDecision> history(
      String sessionId, String shadowRunId, boolean legacy, int limit) {
    if (!isSafeSessionId(sessionId)) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST, "INVALID_SESSION_ID", "sessionId contains invalid characters");
    }
    int resolvedLimit = limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
    Path file;
    if (legacy) {
      file = legacyShadowFile(sessionId);
    } else {
      if (!isSafeRunId(shadowRunId)) {
        throw new PrototypeApiException(
            HttpStatus.BAD_REQUEST,
            "INVALID_SHADOW_RUN_ID",
            "shadowRunId is required and contains invalid characters");
      }
      file = runShadowFile(sessionId, shadowRunId);
    }
    return readHistoryFile(file, resolvedLimit);
  }

  /** Reads and sorts one JSONL history file, newest first, respecting the resolved limit. */
  private List<FishingShadowDecision> readHistoryFile(Path file, int resolvedLimit) {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<FishingShadowDecision> decisions;
    ReentrantReadWriteLock.ReadLock readLock = writer.readLock();
    readLock.lock();
    try {
      String content = Files.readString(file);
      if (content.isEmpty()) {
        return List.of();
      }
      decisions = parseHistoryLines(content);
    } catch (IOException e) {
      log.error("Failed to read shadow history for {}: {}", file, e.getMessage());
      throw new PrototypeApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "HISTORY_READ_FAILED",
          "Failed to read shadow history",
          Map.of("file", file.getFileName().toString()));
    } finally {
      readLock.unlock();
    }
    decisions.sort(Comparator.comparingLong(FishingShadowDecision::tick).reversed());
    return decisions.stream().limit(resolvedLimit).toList();
  }

  /**
   * Parses JSONL shadow history. Lines are parsed independently; every line except a possible
   * unterminated trailing line must parse, otherwise the history is corrupt.
   */
  private List<FishingShadowDecision> parseHistoryLines(String content) throws IOException {
    boolean terminated = content.endsWith("\n");
    String[] parts = content.split("\n", -1);
    int last = parts.length - 1;
    int completeCount = last; // when terminated, the last part is the empty separator remainder
    List<FishingShadowDecision> decisions = new ArrayList<>();
    for (int i = 0; i < completeCount; i++) {
      String line = stripCarriageReturn(parts[i]);
      if (line.isBlank()) {
        continue;
      }
      decisions.add(objectMapper.readValue(line, FishingShadowDecision.class));
    }
    if (!terminated) {
      String trailing = stripCarriageReturn(parts[last]);
      if (!trailing.isBlank()) {
        try {
          decisions.add(objectMapper.readValue(trailing, FishingShadowDecision.class));
        } catch (IOException e) {
          log.debug("Tolerating incomplete trailing shadow history line: {}", e.getMessage());
        }
      }
    }
    return decisions;
  }

  private static String stripCarriageReturn(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  private FishingShadowDecision abstain(
      String sessionId,
      String shadowRunId,
      TickBatchMessage batch,
      String reason,
      Double policyDistance,
      String modelId,
      long startNanos) {
    return new FishingShadowDecision(
        SCHEMA_VERSION,
        TYPE,
        "ABSTAINED",
        modelId,
        sessionId,
        shadowRunId,
        batch.getTick(),
        observedAt(batch.getTimestamp()),
        null,
        null,
        reason,
        policyDistance,
        null,
        latencyMicros(startNanos));
  }

  private boolean isValidFishingAction(Prediction prediction, FishingState fishingState) {
    SemanticAction action = prediction.action();
    if (action == null || !SemanticAction.TYPE_INTERACT_NPC.equals(action.type())) {
      return false;
    }
    if (action.option() == null
        || !FishingActionNormalizer.SUPPORTED_OPTIONS.contains(action.option())) {
      return false;
    }
    String target = FishingActionNormalizer.canonicalize(action.target());
    if (!FishingActionNormalizer.FISHING_SPOT_TARGET.equals(target)) {
      return false;
    }
    FishingState.NearbyNpc targetNpc = prediction.targetNpc();
    if (targetNpc == null) {
      return false;
    }
    String npcName = FishingActionNormalizer.canonicalize(targetNpc.name());
    if (npcName == null || !npcName.contains(FishingActionNormalizer.FISHING_SPOT_TARGET)) {
      return false;
    }
    return targetNpc.plane() == fishingState.player().plane();
  }

  private boolean hasRequiredState(TickBatchMessage batch) {
    return batch.getSessionId() != null
        && !batch.getSessionId().isBlank()
        && batch.getPlayerState() != null
        && hasPlayerPosition(batch.getPlayerState())
        && batch.getInventory() != null
        && batch.getNpcs() != null;
  }

  @SuppressWarnings("unchecked")
  private boolean hasPlayerPosition(Map<String, Object> playerState) {
    Object position = playerState.get("position");
    if (position instanceof Map) {
      Map<String, Object> pos = (Map<String, Object>) position;
      if (hasPosition(pos)) {
        return true;
      }
    }
    return hasPosition(playerState);
  }

  private boolean hasPosition(Map<String, Object> coordinates) {
    boolean hasX =
        coordinateValue(coordinates.get("x")) != null
            || coordinateValue(coordinates.get("worldX")) != null;
    boolean hasY =
        coordinateValue(coordinates.get("y")) != null
            || coordinateValue(coordinates.get("z")) != null
            || coordinateValue(coordinates.get("worldY")) != null;
    return hasX && hasY;
  }

  private Integer coordinateValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      String trimmed = text.trim();
      if (trimmed.isEmpty()) {
        return null;
      }
      try {
        return Integer.parseInt(trimmed);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private String observedAt(String timestamp) {
    return timestamp != null && !timestamp.isBlank() ? timestamp : Instant.now().toString();
  }

  private long latencyMicros(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000L;
  }

  private Path runDirectory(String sessionId) {
    return Paths.get(outputDir, "fishing", "shadow", sessionId);
  }

  private Path runShadowFile(String sessionId, String shadowRunId) {
    return Paths.get(outputDir, "fishing", "shadow", sessionId, shadowRunId + ".jsonl");
  }

  private Path legacyShadowFile(String sessionId) {
    return Paths.get(outputDir, "fishing", "shadow", sessionId + ".jsonl");
  }

  private boolean isSafeSessionId(String sessionId) {
    return sessionId != null && SAFE_SESSION_ID.matcher(sessionId).matches();
  }

  private boolean isSafeRunId(String shadowRunId) {
    return shadowRunId != null && SAFE_RUN_ID.matcher(shadowRunId).matches();
  }

  private String safeDescription(String value) {
    if (value == null) {
      return "(null)";
    }
    String sanitized = value.replaceAll("[^A-Za-z0-9_-]", "?");
    return sanitized.substring(0, Math.min(sanitized.length(), 24));
  }

  private String inactiveReason() {
    if (!enabled) {
      return "AI_PLAYER_DISABLED";
    }
    if (!shadowMode) {
      return "SHADOW_MODE_DISABLED";
    }
    if (controlEnabled) {
      return "INACTIVE_CONTROL_ENABLED";
    }
    return null;
  }
}
