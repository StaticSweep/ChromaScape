package com.chromascape.web.aiplayer.prototype;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads and derives the deterministic analysis of a ChromaScape recording for the fishing prototype.
 *
 * <p>This is the single source of alignment logic shared by preflight, dataset building, and replay.
 * Alignment always prefers the exact {@code details.sourceTick}; missing snapshots fall back to
 * {@code sourceTick - 1}; future snapshots are never used.
 */
@Service
public class FishingRecordingLoader {

  private static final Logger log = LoggerFactory.getLogger(FishingRecordingLoader.class);

  private final TickLogRepository tickLogRepository;
  private final TickSnapshotRepository snapshotRepository;
  private final ObjectMapper objectMapper;

  public FishingRecordingLoader(
      TickLogRepository tickLogRepository,
      TickSnapshotRepository snapshotRepository,
      ObjectMapper objectMapper) {
    this.tickLogRepository = tickLogRepository;
    this.snapshotRepository = snapshotRepository;
    this.objectMapper = objectMapper;
  }

  /** A player action aligned to a persisted snapshot. */
  public record AlignedAction(
      PlayerAction action, TickSnapshot snapshot, long snapshotTick, boolean fallback) {}

  /** Full deterministic context for a recording. */
  public record RecordingContext(
      DebugSession session,
      List<TickSnapshot> snapshots,
      List<PlayerAction> playerActions,
      List<PlayerAction> supportedActions,
      List<PlayerAction> unsupportedActions,
      List<AlignedAction> alignedActions,
      Map<Long, Integer> inventoryTotals,
      int initialInventoryTotal,
      int finalInventoryTotal,
      int catchDelta,
      int positiveInventoryAdditions,
      long firstFishingXp,
      long lastFishingXp,
      boolean fishingXpIncreased,
      boolean playerStatePresent,
      boolean inventoryPresent,
      boolean npcTelemetryPresent,
      boolean overflowDetected,
      int alignmentFallbackCount,
      int unalignedActionCount) {

    /** Returns true when every supported action aligned to a snapshot. */
    public boolean allActionsAligned() {
      return unalignedActionCount == 0;
    }
  }

  /**
   * Loads the full context for a debug session.
   *
   * @param session the resolved debug session
   * @return the derived recording context
   */
  public RecordingContext load(DebugSession session) {
    String sessionId = session.getSessionId();

    List<TickSnapshot> snapshots = snapshotRepository.findBySessionIdOrderByTick(sessionId);
    Map<Long, Integer> inventoryTotals = new HashMap<>();
    for (TickSnapshot snapshot : snapshots) {
      inventoryTotals.put(snapshot.getTick(), totalQuantity(snapshot.getInventory()));
    }

    List<PlayerAction> playerActions = loadPlayerActions(sessionId);
    List<PlayerAction> supportedActions = new ArrayList<>();
    List<PlayerAction> unsupportedActions = new ArrayList<>();
    for (PlayerAction action : playerActions) {
      FishingActionNormalizer.ActionClassification classification =
          FishingActionNormalizer.classify(action);
      switch (classification.actionClass()) {
        case SUPPORTED_FISHING -> supportedActions.add(action);
        case IGNORED -> {
          // ignored
        }
        default -> unsupportedActions.add(action);
      }
    }

    List<AlignedAction> alignedActions = new ArrayList<>();
    int fallbackCount = 0;
    int unalignedCount = 0;
    Map<Long, TickSnapshot> byTick = new HashMap<>();
    for (TickSnapshot snapshot : snapshots) {
      byTick.put(snapshot.getTick(), snapshot);
    }
    for (PlayerAction action : supportedActions) {
      TickSnapshot exact = byTick.get(action.sourceTick());
      if (exact != null) {
        alignedActions.add(new AlignedAction(action, exact, action.sourceTick(), false));
        continue;
      }
      TickSnapshot previous = byTick.get(action.sourceTick() - 1);
      if (previous != null) {
        alignedActions.add(new AlignedAction(action, previous, action.sourceTick() - 1, true));
        fallbackCount++;
        continue;
      }
      unalignedCount++;
    }
    alignedActions.sort(
        Comparator.comparingLong((AlignedAction a) -> a.action().sourceTick())
            .thenComparingInt(a -> a.action().sequence()));

    int initialTotal = snapshots.isEmpty() ? 0 : inventoryTotals.getOrDefault(snapshots.get(0).getTick(), 0);
    int finalTotal =
        snapshots.isEmpty()
            ? 0
            : inventoryTotals.getOrDefault(snapshots.get(snapshots.size() - 1).getTick(), 0);
    int catchDelta = finalTotal - initialTotal;
    int positiveAdditions = 0;
    int previousTotal = -1;
    for (TickSnapshot snapshot : snapshots) {
      int total = inventoryTotals.getOrDefault(snapshot.getTick(), 0);
      if (previousTotal >= 0 && total > previousTotal) {
        positiveAdditions++;
      }
      previousTotal = total;
    }

    long firstXp = fishingXp(snapshots.isEmpty() ? null : snapshots.get(0));
    long lastXp =
        fishingXp(snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1));

    boolean playerStatePresent =
        snapshots.stream().anyMatch(s -> hasPosition(s.getPlayerState()));
    boolean inventoryPresent = snapshots.stream().anyMatch(s -> s.getInventory() != null);
    boolean npcTelemetryPresent = hasFishingSpotTelemetry(snapshots);
    boolean overflowDetected = hasActionQueueOverflow(sessionId);

    return new RecordingContext(
        session,
        snapshots,
        playerActions,
        supportedActions,
        unsupportedActions,
        alignedActions,
        inventoryTotals,
        initialTotal,
        finalTotal,
        catchDelta,
        positiveAdditions,
        firstXp,
        lastXp,
        lastXp > firstXp,
        playerStatePresent,
        inventoryPresent,
        npcTelemetryPresent,
        overflowDetected,
        fallbackCount,
        unalignedCount);
  }

  private List<PlayerAction> loadPlayerActions(String sessionId) {
    List<TickLog> logs =
        tickLogRepository
            .findBySessionIdAndSubsystemIgnoreCaseAndEventTypeIgnoreCaseOrderByTickAsc(
                sessionId, "PLAYER_ACTION", "MENU_CLICK");
    List<PlayerAction> actions = new ArrayList<>();
    for (TickLog logEntry : logs) {
      Map<String, Object> details = toMap(logEntry.getDetails());
      PlayerAction action = PlayerAction.fromDetails(details, logEntry.getTick());
      if (action != null) {
        actions.add(action);
      }
    }
    actions.sort(
        Comparator.comparingLong(PlayerAction::sourceTick)
            .thenComparingInt(PlayerAction::sequence));
    return actions;
  }

  private boolean hasActionQueueOverflow(String sessionId) {
    List<TickLog> playerActionLogs =
        tickLogRepository.findBySessionIdAndSubsystemIgnoreCaseOrderByTickAsc(
            sessionId, "PLAYER_ACTION");
    for (TickLog logEntry : playerActionLogs) {
      if ("ACTION_QUEUE_OVERFLOW".equalsIgnoreCase(logEntry.getEventType())) {
        return true;
      }
    }
    return false;
  }

  private boolean hasFishingSpotTelemetry(List<TickSnapshot> snapshots) {
    for (TickSnapshot snapshot : snapshots) {
      JsonNode npcs = snapshot.getNpcs();
      if (npcs == null) {
        continue;
      }
      for (JsonNode npc : npcs) {
        JsonNode nameNode = npc.get("name");
        if (nameNode != null && nameNode.isTextual()) {
          String name = FishingActionNormalizer.canonicalize(nameNode.asText());
          if (name != null && name.contains(FishingActionNormalizer.FISHING_SPOT_TARGET)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private int totalQuantity(JsonNode inventory) {
    if (inventory == null) {
      return 0;
    }
    int total = 0;
    for (JsonNode item : inventory) {
      JsonNode quantity = item.get("quantity");
      if (quantity != null && quantity.isNumber()) {
        total += quantity.intValue();
      }
    }
    return total;
  }

  private long fishingXp(TickSnapshot snapshot) {
    if (snapshot == null || snapshot.getPlayerState() == null) {
      return -1L;
    }
    JsonNode skills = snapshot.getPlayerState().get("skills");
    if (skills == null || !skills.isObject()) {
      return -1L;
    }
    JsonNode fishing = skills.get("FISHING");
    if (fishing == null) {
      var fields = skills.fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        if ("fishing".equalsIgnoreCase(entry.getKey())) {
          fishing = entry.getValue();
          break;
        }
      }
    }
    if (fishing == null || !fishing.isObject()) {
      return -1L;
    }
    JsonNode xp = fishing.get("xp");
    if (xp == null) {
      xp = fishing.get("experience");
    }
    return xp != null && xp.isNumber() ? xp.longValue() : -1L;
  }

  private boolean hasPosition(JsonNode playerState) {
    if (playerState == null) {
      return false;
    }
    if (playerState.hasNonNull("position") && playerState.get("position").isObject()) {
      JsonNode position = playerState.get("position");
      return (position.hasNonNull("x") || position.hasNonNull("worldX"))
          && (position.hasNonNull("y") || position.hasNonNull("z") || position.hasNonNull("worldY"));
    }
    return (playerState.hasNonNull("x") || playerState.hasNonNull("worldX"))
        && (playerState.hasNonNull("y") || playerState.hasNonNull("z") || playerState.hasNonNull("worldY"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> toMap(JsonNode node) {
    if (node == null) {
      return Map.of();
    }
    try {
      return objectMapper.convertValue(node, Map.class);
    } catch (Exception e) {
      return Map.of();
    }
  }
}
