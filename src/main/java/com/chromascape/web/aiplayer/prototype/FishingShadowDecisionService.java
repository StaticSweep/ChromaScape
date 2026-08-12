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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 */
@Service
public class FishingShadowDecisionService {

  private static final Logger log = LoggerFactory.getLogger(FishingShadowDecisionService.class);
  private static final int SCHEMA_VERSION = 1;
  private static final String TYPE = "AI_PLAYER_SHADOW_DECISION";
  private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
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

  private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
  private static final java.util.concurrent.atomic.AtomicLong SESSION_GENERATION =
      new java.util.concurrent.atomic.AtomicLong();

  /**
   * Per-session in-memory inference state. Mutations are guarded by this instance's monitor.
   *
   * <p>{@code closed} is set by {@link #cleanupSession} under the state lock before the state is
   * removed from {@code sessions}; an evaluation that resolved this exact state before cleanup must
   * refuse to finalize once {@code closed} is true. {@code generation} gives each state instance a
   * unique identity so a later, genuine new session with the same ID can be distinguished from the
   * closed generation it replaced.
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

  public FishingShadowDecisionService(
      FishingActiveModelService activeModelService,
      ObjectMapper objectMapper,
      FishingShadowDecisionWriter writer) {
    this.activeModelService = activeModelService;
    this.objectMapper = objectMapper;
    this.writer = writer;
  }

  /**
   * Evaluates one live tick and returns a decision to broadcast, or null when the service is not
   * operational, the session ID is unsafe, or the tick is stale/duplicate.
   *
   * @param batch the ordered live tick batch
   * @return the shadow decision, or null when inactive or ignored
   */
  public FishingShadowDecision evaluate(TickBatchMessage batch) {
    if (!enabled || !shadowMode || controlEnabled) {
      return null;
    }
    long startNanos = System.nanoTime();
    if (batch == null) {
      return null;
    }
    String sessionId = batch.getSessionId();
    if (!isSafeSessionId(sessionId)) {
      log.warn("Rejecting shadow tick with invalid session id: {}", safeDescription(sessionId));
      return null;
    }

    SessionState state = sessions.computeIfAbsent(sessionId, key -> new SessionState());
    synchronized (state) {
      if (state.closed) {
        return null;
      }
      if (batch.getTick() <= state.latestTick) {
        return null;
      }

      if (!hasRequiredState(batch)) {
        return finalizeLocked(
            state, abstain(sessionId, batch, "ABSTAIN_INVALID_STATE", null, null, startNanos));
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
        return finalizeLocked(
            state, abstain(sessionId, batch, "ABSTAIN_NO_ACTIVE_MODEL", null, null, startNanos));
      }
      String modelId = model.modelId();

      FishingState fishingState = FishingStateExtractor.extract(batch, state.initialInventoryTotal);

      if (fishingState.player().moving()) {
        return finalizeLocked(
            state, abstain(sessionId, batch, "ABSTAIN_MOVING", null, modelId, startNanos));
      }
      if (!fishingState.player().idle() || fishingState.player().animationId() != -1) {
        return finalizeLocked(
            state, abstain(sessionId, batch, "ABSTAIN_BUSY", null, modelId, startNanos));
      }

      Prediction prediction =
          FishingDemonstrationPolicy.predict(
              fishingState, model.examples(), model.maxAcceptedPositionDistance());
      if (prediction.isAbstain()) {
        return finalizeLocked(
            state,
            abstain(
                sessionId,
                batch,
                prediction.abstentionReason(),
                prediction.policyDistance(),
                modelId,
                startNanos));
      }

      if (!isValidFishingAction(prediction, fishingState)) {
        return finalizeLocked(
            state,
            abstain(
                sessionId,
                batch,
                "ABSTAIN_UNSUPPORTED_ACTION",
                prediction.policyDistance(),
                modelId,
                startNanos));
      }

      FishingShadowDecision decision =
          new FishingShadowDecision(
              SCHEMA_VERSION,
              TYPE,
              "PREDICTED",
              modelId,
              sessionId,
              batch.getTick(),
              observedAt(batch.getTimestamp()),
              prediction.action(),
              prediction.targetNpc(),
              null,
              prediction.policyDistance(),
              prediction.matchedExampleTick(),
              latencyMicros(startNanos));
      // Immediately before finalization, verify the session was not closed by a concurrent cleanup.
      // We still hold the state lock so the check cannot race, and finalizeLocked() double-checks.
      if (state.closed) {
        return null;
      }
      return finalizeLocked(state, decision);
    }
  }

  /**
   * Closes and removes the in-memory session state on explicit stop, disconnect, or transport
   * finalization.
   *
   * <p>Cleanup atomically marks the resolved state closed and removes it from {@code sessions}
   * under the state lock. An evaluation that resolved this exact state before cleanup may finish
   * computation, but once {@code closed} is set it can no longer finalize (no counters, no
   * persistence enqueue, no broadcastable decision). Repeated cleanup of the same session (e.g. an
   * explicit stop plus a disconnect) is idempotent, and a later genuine new session with the same
   * ID creates a fresh state generation.
   */
  public void cleanupSession(String sessionId) {
    if (sessionId == null) {
      return;
    }
    SessionState state = sessions.get(sessionId);
    if (state == null) {
      return;
    }
    synchronized (state) {
      state.closed = true;
      sessions.remove(sessionId, state);
    }
    log.info("Cleared fishing shadow session state: {}", sessionId);
  }

  /** Returns the effective feature flags and per-session status from a locked snapshot. */
  public Map<String, Object> status(String sessionId) {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("enabled", enabled);
    status.put("shadowMode", shadowMode);
    status.put("controlEnabled", controlEnabled);
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
    status.put("latestTick", -1L);
    status.put("latestDecision", null);
    status.put("processed", 0);
    status.put("predicted", 0);
    status.put("abstained", 0);

    // Writer lifecycle, queue size, and counters all come from one immutable snapshot so a status
    // consumer can never observe a mixed picture of the persistence pipeline.
    FishingShadowDecisionWriter.WriterStatusSnapshot writerSnapshot = writer.statusSnapshot();
    status.put("persistenceLifecycle", writerSnapshot.lifecycle());
    status.put("persistenceQueueSize", writerSnapshot.queueSize());
    status.put("persistenceEnqueued", writerSnapshot.enqueued());
    status.put("persistenceWritten", writerSnapshot.written());
    status.put("persistenceDropped", writerSnapshot.dropped());
    status.put("persistenceError", writerSnapshot.error());

    SessionState state = resolveStatusState(sessionId);
    if (state != null) {
      synchronized (state) {
        status.put("sessionId", state.latest != null ? state.latest.sessionId() : sessionId);
        status.put("latestTick", state.latestTick);
        status.put("latestDecision", state.latest);
        status.put("processed", state.processed);
        status.put("predicted", state.predicted);
        status.put("abstained", state.abstained);
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
   * Reads persisted shadow history for a session, newest first.
   *
   * <p>Reads take the writer's read lock so a concurrent append can never expose a partially
   * written final line. A lone incomplete trailing line (e.g. from a crash mid-append) is
   * tolerated, while a malformed completed/middle line is treated as corruption and fails the read.
   *
   * @param sessionId the sanitized session id
   * @param limit requested result count (clamped to 1..500, default 50)
   * @return the decisions
   */
  public List<FishingShadowDecision> history(String sessionId, int limit) {
    if (!isSafeSessionId(sessionId)) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST, "INVALID_SESSION_ID", "sessionId contains invalid characters");
    }
    int resolvedLimit = limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
    Path file = shadowFile(sessionId);
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
      log.error("Failed to read shadow history for {}: {}", sessionId, e.getMessage());
      throw new PrototypeApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "HISTORY_READ_FAILED",
          "Failed to read shadow history",
          Map.of("sessionId", sessionId));
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

  /**
   * Finalizes counters/latest tick and enqueues persistence. Caller must hold the session lock.
   *
   * <p>Refuses to finalize a closed session: an evaluation that began before cleanup must not
   * update counters, enqueue persistence, or return a broadcastable decision after closure.
   */
  private FishingShadowDecision finalizeLocked(SessionState state, FishingShadowDecision decision) {
    if (state.closed) {
      return null;
    }
    state.latestTick = decision.tick();
    state.processed++;
    if ("PREDICTED".equals(decision.status())) {
      state.predicted++;
    } else {
      state.abstained++;
    }
    state.latest = decision;
    writer.enqueue(decision);
    return decision;
  }

  private FishingShadowDecision abstain(
      String sessionId,
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

  private SessionState resolveStatusState(String sessionId) {
    if (sessionId != null) {
      return sessions.get(sessionId);
    }
    // Snapshot each candidate under its own lock before comparing ticks so a concurrent evaluation
    // can never expose a half-written SessionState.latest/latestTick pair.
    return sessions.entrySet().stream()
        .map(
            entry -> {
              SessionState state = entry.getValue();
              long tick;
              FishingShadowDecision latest;
              synchronized (state) {
                tick = state.latestTick;
                latest = state.latest;
              }
              return new StatusCandidate(entry.getKey(), tick, latest);
            })
        .filter(c -> c.latest() != null)
        .max(Comparator.comparingLong(StatusCandidate::tick))
        .map(c -> sessions.get(c.sessionId()))
        .orElse(null);
  }

  private record StatusCandidate(String sessionId, long tick, FishingShadowDecision latest) {}

  private String observedAt(String timestamp) {
    return timestamp != null && !timestamp.isBlank() ? timestamp : Instant.now().toString();
  }

  private long latencyMicros(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000L;
  }

  private Path shadowFile(String sessionId) {
    return Paths.get(outputDir, "fishing", "shadow", sessionId + ".jsonl");
  }

  private boolean isSafeSessionId(String sessionId) {
    return sessionId != null && SAFE_SESSION_ID.matcher(sessionId).matches();
  }

  private String safeDescription(String sessionId) {
    if (sessionId == null) {
      return "(null)";
    }
    String sanitized = sessionId.replaceAll("[^A-Za-z0-9_-]", "?");
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
