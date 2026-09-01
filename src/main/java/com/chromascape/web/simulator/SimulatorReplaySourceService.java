package com.chromascape.web.simulator;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.simulator.dto.ReplayCatalogEntry;
import com.chromascape.web.simulator.dto.ReplayCatalogResponse;
import com.chromascape.web.simulator.dto.ReplayFramePageResponse;
import com.chromascape.web.simulator.dto.ReplayFramePageResponse.FrameData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Service that provides tick snapshot data for replay on the OpenRune simulator.
 *
 * <p>Resolves recording IDs to debug sessions and their tick snapshots, providing catalog and
 * paginated frame retrieval endpoints for the OpenRune TickReplayClient.
 */
@Service
@Profile("openrune-legacy")
public class SimulatorReplaySourceService {

  private static final Logger logger = LogManager.getLogger(SimulatorReplaySourceService.class);

  private final DebugSessionRepository debugSessionRepository;
  private final TickSnapshotRepository tickSnapshotRepository;

  @Autowired
  public SimulatorReplaySourceService(
      DebugSessionRepository debugSessionRepository,
      TickSnapshotRepository tickSnapshotRepository) {
    this.debugSessionRepository = debugSessionRepository;
    this.tickSnapshotRepository = tickSnapshotRepository;
  }

  /**
   * Lists recordings that have associated tick snapshot data.
   *
   * @param accountName optional filter by account name
   * @param limit max entries to return (default 50)
   * @param offset pagination offset
   * @return catalog response with entries and total count
   */
  public ReplayCatalogResponse catalog(String accountName, int limit, int offset) {
    List<DebugSession> sessions;
    int total;

    if (accountName != null && !accountName.isBlank()) {
      sessions =
          debugSessionRepository.findByAccountNameOrderByStartTimeDesc(accountName).stream()
              .filter(s -> s.getRecordingId() != null)
              .toList();
    } else {
      sessions =
          debugSessionRepository.findAllByOrderByStartTimeDesc().stream()
              .filter(s -> s.getRecordingId() != null)
              .toList();
    }

    total = sessions.size();

    // Apply pagination
    int fromIndex = Math.min(offset, sessions.size());
    int toIndex = Math.min(offset + limit, sessions.size());
    List<DebugSession> page = sessions.subList(fromIndex, toIndex);

    List<ReplayCatalogEntry> entries = new ArrayList<>();
    for (DebugSession session : page) {
      long tickCount = tickSnapshotRepository.countBySessionId(session.getSessionId());
      long positionFrameCount =
          tickSnapshotRepository.countWithPositionTelemetry(session.getSessionId());
      double positionCoveragePct =
          tickCount > 0 ? ((double) positionFrameCount * 100.0) / (double) tickCount : 0.0;
      boolean replayable = tickCount > 0 && positionFrameCount > 0;
      String replayabilityReason =
          replayable
              ? "Replayable"
              : (tickCount == 0
                  ? "No tick snapshots stored for this recording"
                  : "No position telemetry in tick snapshots");
      entries.add(
          new ReplayCatalogEntry(
              session.getRecordingId(),
              session.getSessionId(),
              session.getAccountName(),
              session.getScriptName(),
              session.getStartTime(),
              session.getEndTime(),
              session.getStartTick(),
              session.getEndTick(),
              tickCount,
              positionFrameCount,
              positionCoveragePct,
              replayable,
              replayabilityReason));
    }

    return new ReplayCatalogResponse(entries, total);
  }

  /** Field name mappings from micro6 (source) to OpenRune (canonical). */
  private static final Map<String, String> PLAYER_STATE_FIELD_MAP =
      Map.of(
          "worldX", "x",
          "worldY", "z",
          "y", "z",
          "currentHp", "hp",
          "currentPrayer", "prayer");

  /**
   * Retrieves a paginated page of tick frames for a recording.
   *
   * <p>Frames are canonicalized at response time: playerState field names are mapped from micro6
   * conventions to OpenRune conventions. Inventory and equipment item arrays are passed through
   * without modification — slot metadata must be present in the source data (emitted by micro6).
   *
   * @param recordingId the recording ID to fetch frames for
   * @param fromTick starting tick (inclusive), defaults to 0
   * @param limit max frames per page (default 100)
   * @return frame page response with frames, pagination info, and session metadata
   */
  public ReplayFramePageResponse frames(String recordingId, long fromTick, int limit) {
    DebugSession session =
        debugSessionRepository
            .findByRecordingId(recordingId)
            .orElseThrow(
                () ->
                    new SimulatorReplayControlException(
                        "No debug session found for recordingId: " + recordingId));

    String sessionId = session.getSessionId();

    // Bounded pagination: fetch limit+1 to detect hasMore without loading the entire tail
    Pageable pageable = PageRequest.of(0, limit + 1, Sort.by("tick"));
    Page<TickSnapshot> snapshotPage =
        tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTick(
            sessionId, fromTick, pageable);

    List<TickSnapshot> snapshots = snapshotPage.getContent();
    boolean hasMore = snapshots.size() > limit;
    List<TickSnapshot> page = hasMore ? snapshots.subList(0, limit) : snapshots;

    List<FrameData> frames = new ArrayList<>();
    for (TickSnapshot snapshot : page) {
      FrameData frame = new FrameData();
      frame.setTick(snapshot.getTick());
      frame.setTimestamp(snapshot.getTimestamp());
      frame.setPlayerState(canonicalizePlayerState(snapshot.getPlayerState()));
      frame.setInventory(canonicalizeItems(snapshot.getInventory()));
      frame.setEquipment(canonicalizeItems(snapshot.getEquipment()));
      frame.setThreats(snapshot.getThreats());
      frame.setProjectiles(snapshot.getProjectiles());
      frame.setCooldowns(snapshot.getCooldowns());
      frame.setQuest(snapshot.getQuest());
      frame.setNpcs(snapshot.getNpcs());
      frame.setGameObjects(snapshot.getGameObjects());
      frame.setGroundObjects(snapshot.getGroundObjects());
      frame.setGroundItems(snapshot.getGroundItems());
      frame.setPlayers(snapshot.getPlayers());
      frame.setWidgets(snapshot.getWidgets());
      frame.setSchemaVersion(snapshot.getSchemaVersion());
      frames.add(frame);
    }

    Long nextFromTick = null;
    if (hasMore && !page.isEmpty()) {
      nextFromTick = page.get(page.size() - 1).getTick() + 1;
    }

    return new ReplayFramePageResponse(
        recordingId,
        sessionId,
        frames,
        nextFromTick,
        hasMore,
        session.getAccountName(),
        session.getStartTick(),
        session.getEndTick());
  }

  /**
   * Canonicalize playerState field names from micro6 conventions to OpenRune conventions.
   *
   * <p>Handles both legacy flat fields (worldX→x, worldY→z, currentHp→hp, currentPrayer→prayer) and
   * new canonical nested fields (position.x→x, position.y/z→z, hp.current→hp,
   * prayer.current→prayer). Other fields are passed through unchanged.
   */
  private JsonNode canonicalizePlayerState(JsonNode playerState) {
    if (playerState == null || !playerState.isObject()) {
      return playerState;
    }
    ObjectNode source = (ObjectNode) playerState;
    ObjectNode canonical = (ObjectNode) source.deepCopy();

    // Legacy flat field mappings
    for (Map.Entry<String, String> entry : PLAYER_STATE_FIELD_MAP.entrySet()) {
      String oldKey = entry.getKey();
      String newKey = entry.getValue();
      if (source.has(oldKey)) {
        canonical.set(newKey, source.get(oldKey));
        canonical.remove(oldKey);
      }
    }

    // New canonical nested position: position.x -> x, position.y or position.z -> z
    if (source.has("position") && source.get("position").isObject()) {
      JsonNode pos = source.get("position");
      if (pos.has("x") && !canonical.has("x")) {
        canonical.set("x", pos.get("x"));
      }
      if (pos.has("z") && !canonical.has("z")) {
        canonical.set("z", pos.get("z"));
      } else if (pos.has("y") && !canonical.has("z")) {
        canonical.set("z", pos.get("y"));
      }
    }

    // New canonical nested hp: hp.current -> hp (numeric) for OpenRune
    if (source.has("hp") && source.get("hp").isObject()) {
      JsonNode hp = source.get("hp");
      if (hp.has("current") && !canonical.has("hp")) {
        canonical.set("hp", hp.get("current"));
      }
    }

    // New canonical nested prayer: prayer.current -> prayer (numeric) for OpenRune
    if (source.has("prayer") && source.get("prayer").isObject()) {
      JsonNode prayer = source.get("prayer");
      if (prayer.has("current") && !canonical.has("prayer")) {
        canonical.set("prayer", prayer.get("current"));
      }
    }

    return canonical;
  }

  /**
   * Pass through item arrays, preserving existing slot fields.
   *
   * <p>micro6 emits inventory/equipment items with real container slot indices. Items from legacy
   * recordings that lack slot metadata are passed through without synthesis — the downstream replay
   * consumer (OpenRune) validates slot presence at replay start and rejects recordings missing slot
   * data.
   */
  private JsonNode canonicalizeItems(JsonNode items) {
    if (items == null || !items.isArray()) {
      return items;
    }
    return items; // pass through; slot must come from source (micro6)
  }
}
