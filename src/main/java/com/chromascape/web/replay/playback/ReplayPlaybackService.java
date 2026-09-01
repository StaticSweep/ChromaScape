package com.chromascape.web.replay.playback;

import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.catalog.ReplayRecordingResolver;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.playback.ReplayPlaybackCursorCodec.Cursor;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackCatalogEntry;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackCatalogResponse;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackFramePageResponse;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackFramePageResponse.FrameData;
import com.chromascape.web.replay.playback.dto.ReplayPlaybackLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads captured debugger evidence for observed playback.
 *
 * <p>This service is deliberately independent of any runtime process. Causal simulation is
 * provided by the xRSPS worker; this path only exposes the original recording's bounded,
 * read-only tick frames to the debugger.
 */
@Service
@Transactional(readOnly = true)
public class ReplayPlaybackService {

  private static final int CATALOG_MAX_LIMIT = 200;
  private static final int FRAME_MAX_LIMIT = 500;

  private final ReplayPlaybackCatalogRepository catalogRepository;
  private final TickSnapshotRepository tickSnapshotRepository;
  private final TickLogRepository tickLogRepository;
  private final ReplayRecordingResolver recordingResolver;
  private final ReplayPlaybackCursorCodec cursorCodec;
  private final ReplayPlaybackFrameCursorCodec frameCursorCodec;

  @Autowired
  public ReplayPlaybackService(
      ReplayPlaybackCatalogRepository catalogRepository,
      TickSnapshotRepository tickSnapshotRepository,
      TickLogRepository tickLogRepository,
      ReplayRecordingResolver recordingResolver,
      ReplayPlaybackCursorCodec cursorCodec,
      ReplayPlaybackFrameCursorCodec frameCursorCodec) {
    this.catalogRepository = catalogRepository;
    this.tickSnapshotRepository = tickSnapshotRepository;
    this.tickLogRepository = tickLogRepository;
    this.recordingResolver = recordingResolver;
    this.cursorCodec = cursorCodec;
    this.frameCursorCodec = frameCursorCodec;
  }

  /** Source-compatible constructor for callers that predate the frame cursor codec. */
  @Deprecated
  public ReplayPlaybackService(
      ReplayPlaybackCatalogRepository catalogRepository,
      TickSnapshotRepository tickSnapshotRepository,
      TickLogRepository tickLogRepository,
      ReplayRecordingResolver recordingResolver,
      ReplayPlaybackCursorCodec cursorCodec) {
    this(
        catalogRepository,
        tickSnapshotRepository,
        tickLogRepository,
        recordingResolver,
        cursorCodec,
        new ReplayPlaybackFrameCursorCodec(new ObjectMapper()));
  }

  /** Lists a keyset page of linked recordings and their bounded evidence aggregates. */
  public ReplayPlaybackCatalogResponse catalog(String accountName, int limit, String cursor) {
    return catalog(accountName, limit, cursor, 0);
  }

  /**
   * Compatibility overload for internal callers that used the pre-v1 offset signature. The HTTP
   * alias is the only public route that supplies an offset.
   */
  @Deprecated
  public ReplayPlaybackCatalogResponse catalog(String accountName, int limit, int offset) {
    return catalog(accountName, limit, null, offset);
  }

  /**
   * Reads the catalog. The offset is intentionally accepted only by the deprecated route; a
   * supplied cursor always wins and resets the offset to zero.
   */
  public ReplayPlaybackCatalogResponse catalog(
      String accountName, int limit, String cursorValue, int offset) {
    String normalizedAccountName = normalizeOptional(accountName);
    int safeLimit = clamp(limit, 1, CATALOG_MAX_LIMIT);
    int safeOffset = Math.max(0, offset);
    Cursor cursor = cursorCodec.decode(cursorValue);
    if (cursor != null) {
      safeOffset = 0;
    }

    List<Object[]> rawRows =
        catalogRepository.findCatalogRows(
            normalizedAccountName,
            cursor != null ? cursor.startTime() : null,
            cursor != null ? cursor.id() : null,
            safeLimit + 1,
            safeOffset);
    List<CatalogRow> rows = new ArrayList<>();
    if (rawRows != null) {
      for (Object[] rawRow : rawRows) {
        rows.add(CatalogRow.from(rawRow));
      }
    }

    boolean hasMore = rows.size() > safeLimit;
    int resultSize = Math.min(rows.size(), safeLimit);
    List<ReplayPlaybackCatalogEntry> entries = new ArrayList<>(resultSize);
    for (int index = 0; index < resultSize; index++) {
      entries.add(toCatalogEntry(rows.get(index)));
    }

    String nextCursor = null;
    if (hasMore && !rows.isEmpty()) {
      CatalogRow finalRow = rows.get(resultSize - 1);
      nextCursor = cursorCodec.encode(finalRow.startTime(), finalRow.cursorId());
    }
    return new ReplayPlaybackCatalogResponse(entries, nextCursor, hasMore);
  }

  /** Retrieves a bounded page of captured frames, with logs attached to returned snapshot ticks. */
  public ReplayPlaybackFramePageResponse frames(String recordingId, long fromTick, int limit) {
    return frames(recordingId, fromTick, null, limit);
  }

  /**
   * Retrieves a bounded page of captured frames using either an initial tick seek or a compound
   * cursor. The two continuation mechanisms are intentionally mutually exclusive.
   */
  public ReplayPlaybackFramePageResponse frames(
      String recordingId, Long fromTick, String cursorValue, int limit) {
    ReplayPlaybackFrameCursorCodec.Cursor cursor = frameCursorCodec.decode(cursorValue);
    if (cursor != null && fromTick != null) {
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "cursor and fromTick cannot be used together");
    }

    String normalizedRecordingId = requireRecordingId(recordingId);
    DebugSession session = recordingResolver.resolve(normalizedRecordingId).debugSession();
    if (session == null) {
      throw ReplayException.notFound(
          ReplayErrorCode.DEBUG_SESSION_NOT_FOUND,
          "No debugger session is linked to this recording");
    }

    int safeLimit = clamp(limit, 1, FRAME_MAX_LIMIT);
    Pageable pageable = PageRequest.of(0, safeLimit + 1);
    Page<TickSnapshot> snapshotPage;
    if (cursor != null) {
      snapshotPage =
          tickSnapshotRepository.findBySessionIdAndAfterCursor(
              session.getSessionId(), cursor.tick(), cursor.id(), pageable);
    } else {
      long safeFromTick = fromTick == null ? 0L : Math.max(0L, fromTick);
      snapshotPage =
          tickSnapshotRepository.findBySessionIdAndTickGreaterThanEqualOrderByTickAscIdAsc(
              session.getSessionId(), safeFromTick, pageable);
    }

    List<TickSnapshot> snapshots =
        snapshotPage == null || snapshotPage.getContent() == null
            ? List.of()
            : snapshotPage.getContent();
    boolean hasMore = snapshots.size() > safeLimit;
    List<TickSnapshot> page =
        hasMore ? new ArrayList<>(snapshots.subList(0, safeLimit)) : new ArrayList<>(snapshots);

    Map<Long, List<ReplayPlaybackLog>> logsByTick = new HashMap<>();
    if (!page.isEmpty()) {
      long minTick = page.stream().mapToLong(snapshot -> snapshot.getTick()).min().orElse(0L);
      long maxTick = page.stream().mapToLong(snapshot -> snapshot.getTick()).max().orElse(0L);
      List<TickLog> logs =
          tickLogRepository.findBySessionIdAndTickBetweenOrderByTickAscIdAsc(
              session.getSessionId(), minTick, maxTick);
      if (logs != null) {
        for (TickLog log : logs) {
          logsByTick.computeIfAbsent(log.getTick(), ignored -> new ArrayList<>()).add(toLog(log));
        }
      }
    }

    List<FrameData> frames = new ArrayList<>(page.size());
    for (TickSnapshot snapshot : page) {
      FrameData frame = toFrame(snapshot);
      frame.setLogs(logsByTick.getOrDefault(snapshot.getTick(), List.of()));
      frames.add(frame);
    }

    String nextCursor = null;
    if (hasMore && !page.isEmpty()) {
      TickSnapshot finalSnapshot = page.get(page.size() - 1);
      if (finalSnapshot.getId() == null || finalSnapshot.getId() <= 0) {
        throw new IllegalStateException("Playback snapshot page returned a row without a valid id");
      }
      nextCursor = frameCursorCodec.encode(finalSnapshot.getTick(), finalSnapshot.getId());
    }
    return new ReplayPlaybackFramePageResponse(
        normalizedRecordingId,
        session.getSessionId(),
        frames,
        nextCursor,
        hasMore,
        session.getAccountName(),
        session.getStartTick(),
        session.getEndTick());
  }

  private ReplayPlaybackCatalogEntry toCatalogEntry(CatalogRow row) {
    double positionCoveragePct =
        row.tickCount() == 0
            ? 0.0
            : ((double) row.positionFrameCount() * 100.0) / (double) row.tickCount();
    boolean replayable = row.tickCount() > 0 && row.positionFrameCount() > 0;
    String reason =
        replayable
            ? "Replayable observed tick evidence"
            : row.tickCount() == 0
                ? "No tick snapshots stored for this recording"
                : "No position telemetry in tick snapshots";
    return new ReplayPlaybackCatalogEntry(
        row.recordingId(),
        row.sessionId(),
        row.accountName(),
        row.scriptName(),
        row.startTime(),
        row.endTime(),
        row.startTick(),
        row.endTick(),
        row.tickCount(),
        row.positionFrameCount(),
        row.logCount(),
        positionCoveragePct,
        replayable,
        reason);
  }

  private FrameData toFrame(TickSnapshot snapshot) {
    FrameData frame = new FrameData();
    frame.setTick(snapshot.getTick());
    frame.setTimestamp(snapshot.getTimestamp());
    frame.setPlayerState(objectOrEmpty(canonicalizePlayerState(snapshot.getPlayerState())));
    frame.setInventory(arrayOrEmpty(snapshot.getInventory()));
    frame.setEquipment(arrayOrEmpty(snapshot.getEquipment()));
    frame.setThreats(arrayOrEmpty(snapshot.getThreats()));
    frame.setProjectiles(arrayOrEmpty(snapshot.getProjectiles()));
    frame.setCooldowns(objectOrEmpty(snapshot.getCooldowns()));
    frame.setQuest(objectOrEmpty(snapshot.getQuest()));
    frame.setQuestHelper(objectOrEmpty(snapshot.getQuestHelper()));
    frame.setNpcs(arrayOrEmpty(snapshot.getNpcs()));
    frame.setGameObjects(arrayOrEmpty(snapshot.getGameObjects()));
    frame.setGroundObjects(arrayOrEmpty(snapshot.getGroundObjects()));
    frame.setGroundItems(arrayOrEmpty(snapshot.getGroundItems()));
    frame.setPlayers(arrayOrEmpty(snapshot.getPlayers()));
    frame.setWidgets(arrayOrEmpty(snapshot.getWidgets()));
    frame.setSchemaVersion(snapshot.getSchemaVersion() == null ? 1 : snapshot.getSchemaVersion());
    return frame;
  }

  private ReplayPlaybackLog toLog(TickLog log) {
    String subsystem = valueOrEmpty(log.getSubsystem());
    String eventType = valueOrEmpty(log.getEventType());
    String eventTypeLower = eventType.toLowerCase(Locale.ROOT);
    String subsystemLower = subsystem.toLowerCase(Locale.ROOT);
    List<String> categories = new ArrayList<>();
    categories.add("ACTION");
    if (eventTypeLower.contains("dialogue") || subsystemLower.equals("dialogue")) {
      categories.add("DIALOGUE");
    }
    if (eventTypeLower.contains("varbit") || subsystemLower.equals("varbit")) {
      categories.add("VARBIT");
    }
    return new ReplayPlaybackLog(
        log.getId(),
        log.getTick(),
        log.getTimestamp(),
        subsystem,
        valueOrEmpty(log.getSeverity()),
        eventType,
        valueOrEmpty(log.getMessage()),
        log.getDetails() == null ? JsonNodeFactory.instance.objectNode() : log.getDetails(),
        log.getCorrelationId(),
        categories);
  }

  /** Normalizes recorder aliases into the replay API's canonical player-state shape. */
  private JsonNode canonicalizePlayerState(JsonNode playerState) {
    if (playerState == null || !playerState.isObject()) {
      return JsonNodeFactory.instance.objectNode();
    }
    ObjectNode source = (ObjectNode) playerState;
    ObjectNode canonical = source.deepCopy();

    putIfMissing(canonical, "x", source.get("worldX"));
    putIfMissing(canonical, "z", source.get("worldY"));
    putIfMissing(canonical, "z", source.get("y"));
    putIfMissing(canonical, "hp", source.get("currentHp"));
    putIfMissing(canonical, "prayer", source.get("currentPrayer"));

    JsonNode position = source.get("position");
    if (position != null && position.isObject()) {
      putIfMissing(canonical, "x", position.get("x"));
      putIfMissing(canonical, "z", position.get("z"));
      putIfMissing(canonical, "z", position.get("y"));
    }

    JsonNode hp = source.get("hp");
    if (hp != null && hp.isObject() && hp.hasNonNull("current")) {
      if (!canonical.has("hp") || !canonical.get("hp").isValueNode()) {
        canonical.set("hp", hp.get("current"));
      }
    }
    JsonNode prayer = source.get("prayer");
    if (prayer != null && prayer.isObject() && prayer.hasNonNull("current")) {
      if (!canonical.has("prayer") || !canonical.get("prayer").isValueNode()) {
        canonical.set("prayer", prayer.get("current"));
      }
    }

    canonical.remove("worldX");
    canonical.remove("worldY");
    canonical.remove("y");
    canonical.remove("currentHp");
    canonical.remove("currentPrayer");
    return canonical;
  }

  private static void putIfMissing(ObjectNode target, String key, JsonNode candidate) {
    if (candidate != null
        && !candidate.isNull()
        && (!target.has(key) || target.get(key) == null || target.get(key).isNull())) {
      target.set(key, candidate);
    }
  }

  private static JsonNode objectOrEmpty(JsonNode value) {
    return value == null || value.isNull() ? JsonNodeFactory.instance.objectNode() : value;
  }

  private static JsonNode arrayOrEmpty(JsonNode value) {
    return value == null || value.isNull() ? JsonNodeFactory.instance.arrayNode() : value;
  }

  private static String requireRecordingId(String recordingId) {
    String normalized = normalizeOptional(recordingId);
    if (normalized == null) {
      throw ReplayException.badRequest(
          ReplayErrorCode.VALIDATION_ERROR, "recordingId is required");
    }
    return normalized;
  }

  private static String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(value, maximum));
  }

  private record CatalogRow(
      long cursorId,
      String recordingId,
      String sessionId,
      String accountName,
      String scriptName,
      LocalDateTime startTime,
      LocalDateTime endTime,
      Long startTick,
      Long endTick,
      long tickCount,
      long positionFrameCount,
      long logCount) {

    private static CatalogRow from(Object[] row) {
      if (row == null || row.length < 12) {
        throw new IllegalStateException("Playback catalog projection returned an incomplete row");
      }
      Long cursorId = asLong(row[0]);
      LocalDateTime startTime = asDateTime(row[5]);
      if (cursorId == null || cursorId <= 0 || startTime == null) {
        throw new IllegalStateException("Playback catalog projection returned an invalid cursor tuple");
      }
      return new CatalogRow(
          cursorId,
          asString(row[1]),
          asString(row[2]),
          asString(row[3]),
          asString(row[4]),
          startTime,
          asDateTime(row[6]),
          asLong(row[7]),
          asLong(row[8]),
          count(row[9]),
          count(row[10]),
          count(row[11]));
    }

    private static long count(Object value) {
      Long count = asLong(value);
      return count == null ? 0L : Math.max(0L, count);
    }

    private static Long asLong(Object value) {
      return value instanceof Number number ? number.longValue() : null;
    }

    private static String asString(Object value) {
      return value == null ? null : value.toString();
    }

    private static LocalDateTime asDateTime(Object value) {
      if (value instanceof LocalDateTime localDateTime) {
        return localDateTime;
      }
      if (value instanceof Timestamp timestamp) {
        return timestamp.toLocalDateTime();
      }
      return null;
    }
  }
}
