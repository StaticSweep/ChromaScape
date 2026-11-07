package com.chromascape.web.replay.conversion;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingActionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts immutable recording evidence into an ordered semantic action stream.
 *
 * <p>The typed action table is authoritative whenever AUTO selects it. Legacy logs are only
 * interpreted when a stable target, option, and source tick are present. Every other record is
 * retained as a gap; movement inferred from snapshot deltas is marked as an approximation. The
 * service has no wall-clock or random inputs, so equal evidence produces an equal checksum.
 */
@Service
@Transactional(readOnly = true)
public class ReplayActionConversionService {

  static final String CONVERTER_VERSION = "legacy-replay-converter-v1";
  private static final String FIXED_CAPTURED_AT = "1970-01-01T00:00:00Z";
  private static final Set<String> SUPPORTED_ACTION_TYPES =
      Set.of(
          "WAIT_UNTIL_TICK",
          "WALK_TO",
          "INTERACT_NPC",
          "INTERACT_OBJECT",
          "INTERACT_GROUND_ITEM",
          "INTERACT_INVENTORY_ITEM",
          "INTERACT_WIDGET",
          "SELECT_DIALOGUE_OPTION",
          "CAST_SPELL",
          "EQUIP_ITEM");

  private final ReplayRecordingActionRepository typedRepository;
  private final TickLogRepository logRepository;
  private final TickSnapshotRepository snapshotRepository;
  private final ObjectMapper objectMapper;
  private final CanonicalProjectionService canonicalProjection;
  private HumanFishingActionReader humanFishing;

  @org.springframework.beans.factory.annotation.Autowired
  public ReplayActionConversionService(
      ReplayRecordingActionRepository typedRepository,
      TickLogRepository logRepository,
      TickSnapshotRepository snapshotRepository,
      ObjectMapper objectMapper,
      CanonicalProjectionService canonicalProjection,
      HumanFishingActionReader humanFishing) {
    this(typedRepository, logRepository, snapshotRepository, objectMapper, canonicalProjection);
    this.humanFishing = humanFishing;
  }

  public ReplayActionConversionService(
      ReplayRecordingActionRepository typedRepository,
      TickLogRepository logRepository,
      TickSnapshotRepository snapshotRepository,
      ObjectMapper objectMapper,
      CanonicalProjectionService canonicalProjection) {
    this.typedRepository = typedRepository;
    this.logRepository = logRepository;
    this.snapshotRepository = snapshotRepository;
    this.objectMapper = objectMapper;
    this.canonicalProjection = canonicalProjection;
  }

  /** Converts all evidence for a session in the supplied inclusive tick range. */
  public ReplayActionConversionResult convert(
      String sessionId, long startTick, long endTick, ConversionMode requestedMode) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId is required");
    }
    if (startTick < 0 || endTick < startTick) {
      throw new IllegalArgumentException("Invalid conversion tick range");
    }
    ConversionMode mode = requestedMode == null ? ConversionMode.AUTO : requestedMode;
    if (humanFishing != null && mode != ConversionMode.LEGACY_BEST_EFFORT) {
      var observed = humanFishing.convert(sessionId, startTick, endTick, mode);
      if (observed.isPresent()) return observed.get();
    }

    List<ReplayRecordingAction> typed =
        typedRepository.findBySessionIdAndTickBetweenOrderByTickAscSequenceAsc(
            sessionId, startTick, endTick);
    List<TickSnapshot> snapshots =
        snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(sessionId, startTick, endTick);
    List<TickLog> logs =
        logRepository.findBySessionIdAndTickBetweenOrderByTickAsc(sessionId, startTick, endTick);

    boolean completeTypedStream = isCompleteTypedStream(typed, logs, sessionId, startTick, endTick);
    if (mode == ConversionMode.CAPTURED_ONLY && !completeTypedStream) {
      return result(
          sessionId,
          startTick,
          endTick,
          mode,
          ConversionQuality.UNSUPPORTED,
          List.of(),
          List.of(
              new ReplayGap(
                  startTick,
                  typed.isEmpty()
                      ? "TYPED_ACTION_STREAM_MISSING"
                      : "TYPED_ACTION_STREAM_INCOMPLETE",
                  typed.isEmpty()
                      ? "No typed recording actions exist in the requested range"
                      : "Typed recording actions do not cover every semantic dispatch",
                  ActionProvenance.DEFAULTED,
                  null)),
          List.of(),
          typed.size(),
          0,
          0.0);
    }
    if (mode != ConversionMode.LEGACY_BEST_EFFORT && !typed.isEmpty()) {
      return capturedResult(sessionId, startTick, endTick, mode, typed, completeTypedStream);
    }
    return legacyResult(sessionId, startTick, endTick, mode, logs, snapshots, typed.size());
  }

  private ReplayActionConversionResult capturedResult(
      String sessionId,
      long startTick,
      long endTick,
      ConversionMode mode,
      List<ReplayRecordingAction> typed,
      boolean completeTypedStream) {
    List<ReplayRecordingAction> ordered = new ArrayList<>(typed);
    ordered.sort(
        Comparator.comparingLong(ReplayRecordingAction::getTick)
            .thenComparingInt(ReplayRecordingAction::getSequence)
            .thenComparing(a -> a.getId() == null ? "" : a.getId().toString()));

    List<ConvertedReplayAction> actions = new ArrayList<>();
    List<ReplayGap> gaps = new ArrayList<>();
    if (!completeTypedStream) {
      gaps.add(
          new ReplayGap(
              startTick,
              "TYPED_ACTION_STREAM_INCOMPLETE",
              "At least one typed action was not captured by the Micro6 semantic dispatch boundary",
              ActionProvenance.CAPTURED,
              null));
    }
    long previousTick = Long.MIN_VALUE;
    int previousSequence = -1;
    for (ReplayRecordingAction entity : ordered) {
      if (entity.getTick() == previousTick && entity.getSequence() <= previousSequence) {
        gaps.add(
            new ReplayGap(
                entity.getTick(),
                "TYPED_ACTION_ORDER_INVALID",
                "Typed action sequence is not strictly increasing for a tick",
                ActionProvenance.CAPTURED,
                null));
      }
      previousTick = entity.getTick();
      previousSequence = entity.getSequence();
      RecordingAction action = fromEntity(entity);
      actions.add(
          new ConvertedReplayAction(
              action, toActionProvenance(entity), "typed dispatch", null, null));
    }

    ConversionQuality quality =
        completeTypedStream && gaps.isEmpty()
            ? ConversionQuality.CERTIFIED
            : ConversionQuality.BEST_EFFORT;
    return result(
        sessionId,
        startTick,
        endTick,
        mode,
        quality,
        actions,
        gaps,
        List.of(),
        ordered.size(),
        0,
        actions.isEmpty() ? 0.0 : 100.0);
  }

  /**
   * A typed row is certification evidence only when it came from the Micro6 semantic dispatch hook
   * and still belongs to the requested immutable session/range. Rows written by a converter or
   * operator are useful evidence, but cannot upgrade a manifest to CERTIFIED.
   */
  private static boolean isCompleteTypedStream(
      List<ReplayRecordingAction> typed,
      List<TickLog> logs,
      String sessionId,
      long startTick,
      long endTick) {
    if (typed == null || typed.isEmpty()) {
      return false;
    }
    for (ReplayRecordingAction action : typed) {
      if (action == null
          || !sessionId.equals(action.getSessionId())
          || action.getTick() < startTick
          || action.getTick() > endTick
          || !RecordingAction.SOURCE_MICRO6_SEMANTIC_DISPATCH.equals(action.getSource())
          || !RecordingAction.PROVENANCE_CAPTURED.equals(action.getProvenance())
          || !RecordingAction.SCHEMA.equals(action.getSchema())
          || action.getSequence() < 0
          || action.getActionType() == null
          || action.getArgumentsJson() == null
          || (!"WAIT_UNTIL_TICK".equals(action.getActionType())
              && (action.getTargetStableId() == null || action.getTargetStableId().isBlank()))) {
        return false;
      }
    }

    // A typed row by itself cannot prove that the client captured every dispatch. Micro6 emits a
    // legacy PLAYER_ACTION/MENU_CLICK diagnostic for the same dispatch, carrying the source tick
    // and sequence. Require a one-to-one key match and the same inferred semantic type; otherwise
    // a recording containing one typed action plus an untyped/unsupported menu click would be
    // incorrectly promoted to CERTIFIED.
    Map<ActionKey, TickLog> legacyByKey = new HashMap<>();
    if (logs == null || logs.isEmpty()) {
      return false;
    }
    for (TickLog log : logs) {
      if (log == null) {
        continue;
      }
      String subsystem = log.getSubsystem() == null ? "" : log.getSubsystem().trim();
      String event = log.getEventType() == null ? "" : log.getEventType().trim();
      if (!"PLAYER_ACTION".equalsIgnoreCase(subsystem) || !"MENU_CLICK".equalsIgnoreCase(event)) {
        if ("PLAYER_ACTION".equalsIgnoreCase(subsystem)
            && "ACTION_QUEUE_OVERFLOW".equalsIgnoreCase(event)) {
          return false;
        }
        continue;
      }
      Map<String, Object> details = jsonMap(log.getDetails());
      long sourceTick =
          number(details.get("sourceTick"), log.getTick() == null ? -1 : log.getTick());
      int sequence = intValue(details.get("sequence"), -1);
      if (sourceTick < startTick || sourceTick > endTick || sequence < 0) {
        return false;
      }
      ActionKey key = new ActionKey(sourceTick, sequence);
      if (legacyByKey.putIfAbsent(key, log) != null) {
        return false;
      }
      if (actionType("MENU_CLICK", "PLAYER_ACTION", details) == null) {
        return false;
      }
    }
    if (legacyByKey.isEmpty()) {
      return false;
    }

    Set<ActionKey> typedKeys = new HashSet<>();
    for (ReplayRecordingAction action : typed) {
      ActionKey key = new ActionKey(action.getTick(), action.getSequence());
      if (!typedKeys.add(key) || !legacyByKey.containsKey(key)) {
        return false;
      }
      Map<String, Object> details = jsonMap(legacyByKey.get(key).getDetails());
      String inferredType = actionType("MENU_CLICK", "PLAYER_ACTION", details);
      if (!action.getActionType().equals(inferredType)) {
        return false;
      }
      String legacyOption = text(details, "option", "optionText", "action");
      String typedOption = text(jsonMap(action.getArgumentsJson()), "option");
      if (legacyOption != null
          && typedOption != null
          && !normalizeOption(legacyOption).equals(normalizeOption(typedOption))) {
        return false;
      }
      String typedStableId = action.getTargetStableId();
      String legacyStableId = stableTarget(details);
      if (!"WAIT_UNTIL_TICK".equals(action.getActionType())
          && (typedStableId == null
              || typedStableId.isBlank()
              || legacyStableId == null
              || !typedStableId.equals(legacyStableId))) {
        return false;
      }
    }
    return typedKeys.size() == legacyByKey.size();
  }

  private ReplayActionConversionResult legacyResult(
      String sessionId,
      long startTick,
      long endTick,
      ConversionMode mode,
      List<TickLog> sourceLogs,
      List<TickSnapshot> snapshots,
      int typedCount) {
    List<TickLog> logs = new ArrayList<>(sourceLogs == null ? List.of() : sourceLogs);
    logs.sort(
        Comparator.comparingLong(
                (TickLog log) -> log.getTick() == null ? Long.MAX_VALUE : log.getTick())
            .thenComparingLong(log -> log.getId() == null ? Long.MAX_VALUE : log.getId()));
    List<TickSnapshot> orderedSnapshots =
        new ArrayList<>(snapshots == null ? List.of() : snapshots);
    orderedSnapshots.sort(
        Comparator.<TickSnapshot>comparingLong(
                s -> s.getTick() == null ? Long.MAX_VALUE : s.getTick())
            .thenComparingLong(s -> s.getId() == null ? Long.MAX_VALUE : s.getId()));

    Map<Long, TickSnapshot> snapshotByTick = new TreeMap<>();
    for (TickSnapshot snapshot : orderedSnapshots) {
      if (snapshot.getTick() != null) {
        snapshotByTick.putIfAbsent(snapshot.getTick(), snapshot);
      }
    }
    Map<Long, Set<Integer>> usedSequences = new HashMap<>();
    List<ConvertedReplayAction> actions = new ArrayList<>();
    List<ReplayGap> gaps = new ArrayList<>();
    int convertibleLogs = 0;

    for (TickLog log : logs) {
      long enclosingTick = log.getTick() == null ? startTick : log.getTick();
      LegacyCandidate candidate = inferLogAction(sessionId, log, snapshotByTick);
      if (candidate == null) {
        gaps.add(
            new ReplayGap(
                enclosingTick,
                legacyGapCode(log),
                legacyGapMessage(log),
                ActionProvenance.INFERRED,
                log.getId()));
        continue;
      }
      long actionTick = candidate.action().getTick();
      int sequence = allocateSequence(usedSequences, actionTick, candidate.requestedSequence());
      candidate.action().setSequence(sequence);
      actions.add(
          new ConvertedReplayAction(
              candidate.action(),
              ActionProvenance.INFERRED,
              candidate.reason(),
              log.getId(),
              null));
      convertibleLogs++;
    }

    List<ReplayApproximation> approximations = new ArrayList<>();
    addMovementApproximations(
        sessionId, orderedSnapshots, usedSequences, actions, approximations, gaps);

    actions.sort(
        Comparator.comparingLong((ConvertedReplayAction a) -> a.action().getTick())
            .thenComparingInt(a -> a.action().getSequence())
            .thenComparingLong(a -> a.sourceLogId() == null ? Long.MAX_VALUE : a.sourceLogId())
            .thenComparing(a -> a.action().getActionType()));
    gaps.sort(Comparator.comparingLong(ReplayGap::tick).thenComparing(ReplayGap::code));
    approximations.sort(
        Comparator.comparingLong(ReplayApproximation::tick)
            .thenComparing(ReplayApproximation::code));

    ConversionQuality quality =
        actions.isEmpty() ? ConversionQuality.UNSUPPORTED : ConversionQuality.BEST_EFFORT;
    double coverage =
        logs.isEmpty()
            ? (actions.isEmpty() ? 0.0 : 100.0)
            : (100.0 * convertibleLogs / logs.size());
    return result(
        sessionId,
        startTick,
        endTick,
        mode,
        quality,
        actions,
        gaps,
        approximations,
        typedCount,
        logs.size(),
        roundCoverage(coverage));
  }

  private LegacyCandidate inferLogAction(
      String sessionId, TickLog log, Map<Long, TickSnapshot> snapshotByTick) {
    if (log == null || log.getTick() == null || log.getEventType() == null) {
      return null;
    }
    String event = log.getEventType().trim().toUpperCase();
    String subsystem = log.getSubsystem() == null ? "" : log.getSubsystem().trim().toUpperCase();
    if (!("MENU_CLICK".equals(event) || "PLAYER_ACTION".equals(subsystem))) {
      return null;
    }
    Map<String, Object> details = jsonMap(log.getDetails());
    String actionType = actionType(event, subsystem, details);
    if (actionType == null || !SUPPORTED_ACTION_TYPES.contains(actionType)) {
      return null;
    }
    long sourceTick = number(details.get("sourceTick"), log.getTick());
    if (sourceTick < 0) {
      return null;
    }
    String option = text(details, "option", "optionText", "action");
    if (option != null) {
      option = option.trim().toLowerCase();
    }
    int requestedSequence = intValue(details.get("sequence"), 0);
    if (requestedSequence < 0) {
      requestedSequence = 0;
    }

    Map<String, Object> target = new LinkedHashMap<>();
    String stableId = stableTarget(details);
    String targetKind = targetKind(actionType, details);
    if (requiresTarget(actionType)) {
      if (stableId == null) {
        stableId = resolveStableIdFromSnapshot(snapshotByTick.get(sourceTick), targetKind, details);
      }
      if (stableId == null) {
        return null;
      }
      target.put("kind", targetKind);
      target.put("stableId", stableId);
      copyNumericIfPresent(target, "definitionId", details.get("definitionId"));
      copyNumericIfPresent(target, "definitionId", details.get("identifier"));
    }

    Map<String, Object> arguments = new LinkedHashMap<>();
    if (option != null && !option.isBlank()) {
      arguments.put("option", option);
    }
    copyCanonicalArgument(arguments, "menuAction", details.get("menuAction"));
    copyCanonicalArgument(arguments, "identifier", details.get("identifier"));
    copyCanonicalArgument(arguments, "param0", details.get("param0"));
    copyCanonicalArgument(arguments, "param1", details.get("param1"));
    copyCanonicalArgument(arguments, "slot", details.get("slot"));
    copyCanonicalArgument(arguments, "itemId", details.get("itemId"));
    copyCanonicalArgument(arguments, "widgetId", details.get("widgetId"));
    copyCanonicalArgument(arguments, "spellId", details.get("spellId"));
    if (!requiresTarget(actionType) && "WAIT_UNTIL_TICK".equals(actionType)) {
      arguments.put("untilTick", sourceTick);
    }

    RecordingAction action = new RecordingAction();
    action.setSchema(RecordingAction.SCHEMA);
    action.setSessionId(sessionId);
    action.setTick(sourceTick);
    action.setSequence(requestedSequence);
    action.setActionType(actionType);
    action.setTarget(target);
    action.setArguments(arguments);
    action.setSource("MICRO6_LEGACY_CONVERSION");
    action.setConfidence(new BigDecimal("0.500"));
    action.setCapturedAt(capturedAt(log.getTimestamp()));
    return new LegacyCandidate(action, requestedSequence, "legacy structured menu evidence");
  }

  private void addMovementApproximations(
      String sessionId,
      List<TickSnapshot> snapshots,
      Map<Long, Set<Integer>> usedSequences,
      List<ConvertedReplayAction> actions,
      List<ReplayApproximation> approximations,
      List<ReplayGap> gaps) {
    Position previous = null;
    for (TickSnapshot snapshot : snapshots) {
      Position current = position(snapshot == null ? null : snapshot.getPlayerState());
      if (snapshot == null || snapshot.getTick() == null || current == null) {
        previous = current;
        continue;
      }
      if (previous != null && !previous.equals(current)) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("kind", "TILE");
        target.put("stableId", "tile:" + current.x() + ":" + current.y() + ":" + current.plane());
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(
            "destination", Map.of("x", current.x(), "y", current.y(), "plane", current.plane()));
        RecordingAction action = new RecordingAction();
        action.setSchema(RecordingAction.SCHEMA);
        action.setSessionId(sessionId);
        action.setTick(snapshot.getTick());
        action.setSequence(allocateSequence(usedSequences, snapshot.getTick(), 0));
        action.setActionType("WALK_TO");
        action.setTarget(target);
        action.setArguments(arguments);
        action.setSource("MICRO6_LEGACY_CONVERSION");
        action.setConfidence(new BigDecimal("0.250"));
        action.setCapturedAt(capturedAt(snapshot.getTimestamp()));
        actions.add(
            new ConvertedReplayAction(
                action,
                ActionProvenance.INFERRED,
                "first changed observed tile; route was not captured",
                null,
                snapshot.getId()));
        approximations.add(
            new ReplayApproximation(
                snapshot.getTick(),
                "LEGACY_MOVEMENT_ROUTE_INCOMPLETE",
                "WALK_TO was inferred from a snapshot delta; intermediate route and dispatch are"
                    + " unknown",
                ActionProvenance.INFERRED,
                snapshot.getId()));
        if (Math.max(Math.abs(current.x() - previous.x()), Math.abs(current.y() - previous.y()))
            > 1) {
          gaps.add(
              new ReplayGap(
                  snapshot.getTick(),
                  "LEGACY_MOVEMENT_GAP",
                  "Snapshot delta skipped one or more intermediate tiles",
                  ActionProvenance.INFERRED,
                  null));
        }
      }
      previous = current;
    }
  }

  private ReplayActionConversionResult result(
      String sessionId,
      long startTick,
      long endTick,
      ConversionMode mode,
      ConversionQuality quality,
      List<ConvertedReplayAction> actions,
      List<ReplayGap> gaps,
      List<ReplayApproximation> approximations,
      int typedCount,
      int legacyCount,
      double coverage) {
    ObjectNode document = objectMapper.createObjectNode();
    document.put("converterVersion", CONVERTER_VERSION);
    document.put("sessionId", sessionId);
    document.put("startTick", startTick);
    document.put("endTick", endTick);
    document.put("mode", mode.name());
    document.put("quality", quality.name());
    document.put("actionCoveragePct", coverage);
    document.put("typedActionCount", typedCount);
    document.put("legacyLogCount", legacyCount);
    var actionArray = document.putArray("actions");
    for (ConvertedReplayAction converted : actions) {
      ObjectNode entry = actionArray.addObject();
      entry.set("action", objectMapper.valueToTree(converted.action()));
      entry.put("provenance", converted.provenance().name());
      entry.put("provenanceReason", converted.provenanceReason());
      if (converted.sourceLogId() != null) {
        entry.put("sourceLogId", converted.sourceLogId());
      }
      if (converted.sourceSnapshotId() != null) {
        entry.put("sourceSnapshotId", converted.sourceSnapshotId());
      }
    }
    var gapArray = document.putArray("gaps");
    for (ReplayGap gap : gaps) {
      ObjectNode entry = gapArray.addObject();
      entry.put("tick", gap.tick());
      entry.put("code", gap.code());
      entry.put("message", gap.message());
      entry.put("provenance", gap.provenance().name());
      if (gap.sourceLogId() != null) {
        entry.put("sourceLogId", gap.sourceLogId());
      }
    }
    var approximationArray = document.putArray("approximations");
    for (ReplayApproximation approximation : approximations) {
      ObjectNode entry = approximationArray.addObject();
      entry.put("tick", approximation.tick());
      entry.put("code", approximation.code());
      entry.put("message", approximation.message());
      entry.put("provenance", approximation.provenance().name());
      if (approximation.sourceSnapshotId() != null) {
        entry.put("sourceSnapshotId", approximation.sourceSnapshotId());
      }
    }
    String checksum =
        canonicalProjection.sha256Hex(canonicalProjection.canonicalJsonBytes(document));
    return new ReplayActionConversionResult(
        sessionId,
        startTick,
        endTick,
        mode,
        quality,
        actions,
        gaps,
        approximations,
        typedCount,
        legacyCount,
        coverage,
        checksum);
  }

  private RecordingAction fromEntity(ReplayRecordingAction entity) {
    RecordingAction action = new RecordingAction();
    action.setSchema(entity.getSchema());
    action.setRecordingId(entity.getRecordingId());
    action.setSessionId(entity.getSessionId());
    action.setTick(entity.getTick());
    action.setSequence(entity.getSequence());
    action.setActionType(entity.getActionType());
    action.setTarget(
        entity.getTargetJson() == null ? new LinkedHashMap<>() : jsonMap(entity.getTargetJson()));
    action.setArguments(
        entity.getArgumentsJson() == null
            ? new LinkedHashMap<>()
            : jsonMap(entity.getArgumentsJson()));
    action.setSource(entity.getSource());
    action.setProvenance(
        entity.getProvenance() == null
            ? provenanceForSource(entity.getSource()).name()
            : entity.getProvenance());
    action.setContentChecksum(entity.getContentChecksum());
    action.setConfidence(entity.getConfidence());
    action.setCapturedAt(entity.getCapturedAt().toInstant(ZoneOffset.UTC).toString());
    return action;
  }

  private static ActionProvenance toActionProvenance(ReplayRecordingAction entity) {
    String provenance = entity.getProvenance();
    if (provenance == null || provenance.isBlank()) {
      return provenanceForSource(entity.getSource());
    }
    try {
      return ActionProvenance.valueOf(provenance);
    } catch (IllegalArgumentException ignored) {
      return ActionProvenance.DEFAULTED;
    }
  }

  private static ActionProvenance provenanceForSource(String source) {
    return switch (source == null ? "" : source) {
      case RecordingAction.SOURCE_MICRO6_SEMANTIC_DISPATCH -> ActionProvenance.CAPTURED;
      case "MICRO6_LEGACY_CONVERSION" -> ActionProvenance.INFERRED;
      case "OPERATOR_SUPPLIED" -> ActionProvenance.OPERATOR_SUPPLIED;
      case "DEFAULTED" -> ActionProvenance.DEFAULTED;
      default -> ActionProvenance.DEFAULTED;
    };
  }

  private static int allocateSequence(Map<Long, Set<Integer>> used, long tick, int requested) {
    Set<Integer> sequences = used.computeIfAbsent(tick, ignored -> new HashSet<>());
    int candidate = Math.max(0, requested);
    while (!sequences.add(candidate)) {
      candidate++;
    }
    return candidate;
  }

  private static boolean requiresTarget(String actionType) {
    return !"WAIT_UNTIL_TICK".equals(actionType);
  }

  private static String actionType(String event, String subsystem, Map<String, Object> details) {
    String menuAction = text(details, "menuAction", "actionType", "type");
    String marker = (menuAction == null ? "" : menuAction) + " " + event + " " + subsystem;
    String upper = marker.toUpperCase();
    if (upper.contains("NPC")) return "INTERACT_NPC";
    if (upper.contains("OBJECT")) return "INTERACT_OBJECT";
    if (upper.contains("GROUND") && upper.contains("ITEM")) return "INTERACT_GROUND_ITEM";
    if (upper.contains("INVENTORY") || upper.contains("ITEM")) return "INTERACT_INVENTORY_ITEM";
    if (upper.contains("WIDGET")) return "INTERACT_WIDGET";
    if (upper.contains("DIALOGUE") || upper.contains("DIALOG")) return "SELECT_DIALOGUE_OPTION";
    if (upper.contains("SPELL")) return "CAST_SPELL";
    if (upper.contains("EQUIP")) return "EQUIP_ITEM";
    if (upper.contains("WALK") || upper.contains("MOVE")) return "WALK_TO";
    if (upper.contains("WAIT")) return "WAIT_UNTIL_TICK";
    return null;
  }

  private static String targetKind(String actionType, Map<String, Object> details) {
    if ("INTERACT_NPC".equals(actionType)) return "NPC";
    if ("INTERACT_OBJECT".equals(actionType)) return "OBJECT";
    if ("INTERACT_GROUND_ITEM".equals(actionType)) return "GROUND_ITEM";
    if ("INTERACT_INVENTORY_ITEM".equals(actionType) || "EQUIP_ITEM".equals(actionType)) {
      return "INVENTORY_ITEM";
    }
    if ("INTERACT_WIDGET".equals(actionType)
        || "SELECT_DIALOGUE_OPTION".equals(actionType)
        || "CAST_SPELL".equals(actionType)) return "WIDGET";
    if ("WALK_TO".equals(actionType)) return "TILE";
    return text(details, "targetKind", "kind") == null
        ? "TILE"
        : text(details, "targetKind", "kind");
  }

  private static String stableTarget(Map<String, Object> details) {
    Object direct = details.get("stableId");
    if (!(direct instanceof String)) direct = details.get("targetStableId");
    if (direct instanceof String value && !value.isBlank()) return value;
    Object target = details.get("target");
    if (target instanceof Map<?, ?> map) {
      Object stable = map.get("stableId");
      if (stable instanceof String value && !value.isBlank()) return value;
    }
    return null;
  }

  private static String resolveStableIdFromSnapshot(
      TickSnapshot snapshot, String targetKind, Map<String, Object> details) {
    if (snapshot == null || !"NPC".equals(targetKind)) return null;
    int identifier = intValue(details.get("identifier"), Integer.MIN_VALUE);
    JsonNode npcs = snapshot.getNpcs();
    if (npcs == null || !npcs.isArray()) return null;
    String match = null;
    for (JsonNode npc : npcs) {
      int id = npc.path("npcId").asInt(npc.path("id").asInt(Integer.MIN_VALUE));
      if (id != identifier) continue;
      String stable = npc.path("stableId").asText(null);
      if (stable == null || stable.isBlank() || match != null) return null;
      match = stable;
    }
    return match;
  }

  private static boolean copyNumericIfPresent(
      Map<String, Object> target, String key, Object value) {
    if (value instanceof Number number) {
      target.put(key, number.intValue());
      return true;
    }
    return false;
  }

  private static void copyCanonicalArgument(Map<String, Object> target, String key, Object value) {
    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
      target.put(key, value);
    }
  }

  private static String text(Map<String, Object> values, String... keys) {
    for (String key : keys) {
      Object value = values.get(key);
      if (value instanceof String string && !string.isBlank()) return string;
    }
    return null;
  }

  private static int intValue(Object value, int fallback) {
    if (value instanceof Number number) return number.intValue();
    if (value instanceof String string) {
      try {
        return Integer.parseInt(string.trim());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static long number(Object value, long fallback) {
    if (value instanceof Number n) return n.longValue();
    if (value instanceof String s) {
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static Map<String, Object> jsonMap(JsonNode node) {
    if (node == null || !node.isObject()) return new LinkedHashMap<>();
    Map<String, Object> map = new LinkedHashMap<>();
    node.fields().forEachRemaining(entry -> map.put(entry.getKey(), jsonValue(entry.getValue())));
    return map;
  }

  private static Object jsonValue(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isObject()) return jsonMap(node);
    if (node.isArray()) {
      List<Object> values = new ArrayList<>();
      node.forEach(child -> values.add(jsonValue(child)));
      return values;
    }
    if (node.isBoolean()) return node.booleanValue();
    if (node.isIntegralNumber()) return node.longValue();
    if (node.isFloatingPointNumber()) return node.decimalValue();
    return node.asText();
  }

  private static String capturedAt(LocalDateTime timestamp) {
    return timestamp == null ? FIXED_CAPTURED_AT : timestamp.toInstant(ZoneOffset.UTC).toString();
  }

  private static String normalizeOption(String value) {
    return value == null
        ? ""
        : value
            .replaceAll("(?i)<[^>]+>", "")
            .replace('\u00a0', ' ')
            .trim()
            .replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);
  }

  private static String legacyGapCode(TickLog log) {
    if (log.getEventType() == null) return "LEGACY_EVENT_TYPE_MISSING";
    if (log.getDetails() == null || !log.getDetails().isObject()) return "LEGACY_DETAILS_MISSING";
    return "LEGACY_ACTION_UNSUPPORTED_OR_UNRESOLVED";
  }

  private static String legacyGapMessage(TickLog log) {
    if (log.getEventType() == null) return "Legacy log has no event type";
    if (log.getDetails() == null || !log.getDetails().isObject()) {
      return "Legacy action log has no structured details";
    }
    return "Legacy log could not provide a supported action with a stable target and arguments";
  }

  private static Position position(JsonNode playerState) {
    if (playerState == null || !playerState.isObject()) return null;
    JsonNode p = playerState.has("position") ? playerState.get("position") : playerState;
    if (p == null || !p.isObject() || !p.has("x") || !(p.has("y") || p.has("z"))) return null;
    return new Position(
        p.path("x").asInt(),
        p.has("y") ? p.path("y").asInt() : p.path("z").asInt(),
        p.path("plane").asInt(0));
  }

  private static double roundCoverage(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  private record Position(int x, int y, int plane) {}

  private record ActionKey(long tick, int sequence) {}

  private record LegacyCandidate(RecordingAction action, int requestedSequence, String reason) {}
}
