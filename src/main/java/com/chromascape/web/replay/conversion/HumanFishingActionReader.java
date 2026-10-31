package com.chromascape.web.replay.conversion;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.recording.provenance.BehaviorRunContextService;
import com.chromascape.web.recording.provenance.CanonicalFrameStore;
import com.chromascape.web.recording.provenance.CanonicalTickFrame;
import com.chromascape.web.recording.provenance.HumanFishingFrameContract;
import com.chromascape.web.recording.provenance.ProvenanceException;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Bounded, read-only conversion of witnessed manual lure interactions; never bot dispatch rows. */
@Service
public class HumanFishingActionReader {
  private final DebugSessionRepository sessions;
  private final BehaviorRunContextService contexts;
  private final CanonicalFrameStore frames;
  private final ObjectMapper json;
  private final CanonicalProjectionService projection;

  public HumanFishingActionReader(
      DebugSessionRepository sessions,
      BehaviorRunContextService contexts,
      CanonicalFrameStore frames,
      ObjectMapper json,
      CanonicalProjectionService projection) {
    this.sessions = sessions;
    this.contexts = contexts;
    this.frames = frames;
    this.json = json;
    this.projection = projection;
  }

  public Optional<ReplayActionConversionResult> convert(
      String session, long start, long end, ConversionMode mode) {
    var row = sessions.findBySessionId(session).orElse(null);
    if (row == null || row.getRecordingId() == null) return Optional.empty();
    String recording = row.getRecordingId();
    JsonNode context = contexts.context(recording);
    if (context == null
        || !"HUMAN".equals(context.path("dataOrigin").asText())
        || !"FISHING_TRAINING".equals(context.path("taskKey").asText())) return Optional.empty();
    contexts.requireTrainingContext(recording);
    List<CanonicalTickFrame> all = new ArrayList<>();
    BigInteger cursor = BigInteger.ZERO;
    long bytes = 0;
    while (true) {
      var page = frames.page(recording, cursor, 200);
      if (page.isEmpty()) break;
      for (var frame : page) {
        if (!frame.sequence().equals(cursor.add(BigInteger.ONE))) fail("HUMAN_FRAME_SEQUENCE_GAP");
        bytes += frame.payload().toString().getBytes(StandardCharsets.UTF_8).length;
        if (all.size() >= 5000 || bytes > 64L * 1024 * 1024) fail("HUMAN_IMPORT_LIMIT");
        all.add(frame);
        cursor = frame.sequence();
      }
    }
    return Optional.of(convertFrames(session, recording, start, end, mode, context, all));
  }

  ReplayActionConversionResult convertFrames(
      String session,
      String recording,
      long start,
      long end,
      ConversionMode mode,
      JsonNode context,
      List<CanonicalTickFrame> all) {
    if (all.isEmpty() || all.size() > 5000) fail("HUMAN_CAPTURE_MISSING_OR_TOO_LARGE");
    var byTick = new LinkedHashMap<Long, CanonicalTickFrame>();
    var eventIds = new HashSet<String>();
    String observer = null;
    BigInteger sequence = BigInteger.ZERO;
    long lastTick = -1;
    long callbacks = 0, accepted = 0, eventCount = 0;
    long initialFish = -1;
    BigInteger lastEvent = BigInteger.ZERO;
    JsonNode previousCapture = null;
    for (var frozen : all) {
      JsonNode frame = frozen.payload();
      CanonicalTickFrame.validate(frame, context);
      HumanFishingFrameContract.validate(frame, context);
      JsonNode capture = frame.path("humanCapture");
      long tick = frame.path("tick").asLong();
      if (!frozen.sequence().equals(sequence.add(BigInteger.ONE))
          || lastTick >= 0 && tick != lastTick + 1
          || previousCapture != null && previousCapture.path("terminal").asBoolean())
        fail("HUMAN_FRAME_ORDER_INVALID");
      if (observer != null && !observer.equals(capture.path("producerId").asText()))
        fail("HUMAN_OBSERVER_RESTART");
      if (!capture.path("issues").isEmpty() || capture.path("dropped").asLong() != 0)
        fail("CAPTURE_INCOMPLETE");
      long fish = 0;
      for (JsonNode item : frame.path("inventory"))
        if (item.path("itemId").asInt() == 335 || item.path("itemId").asInt() == 331)
          fish += item.path("quantity").asLong();
      if (initialFish < 0) initialFish = fish;
      JsonNode delta =
          frame.path("semanticSnapshot").path("inventory").path("positiveDeltaFromStart");
      if (!delta.isIntegralNumber() || delta.asLong() != Math.max(0, fish - initialFish))
        fail("HUMAN_INVENTORY_DELTA_MISMATCH");
      for (JsonNode event : frame.path("semanticEvents")) {
        BigInteger incoming = event.path("eventSequence").bigIntegerValue();
        if (!incoming.equals(lastEvent.add(BigInteger.ONE))) fail("EVENT_SEQUENCE_GAP");
        lastEvent = incoming;
      }
      if (capture.path("observedCallbacks").asLong() < callbacks
          || capture.path("acceptedIntents").asLong() < accepted) fail("HUMAN_COUNTER_REGRESSION");
      eventCount += capture.path("actions").size();
      callbacks = capture.path("observedCallbacks").asLong();
      accepted = capture.path("acceptedIntents").asLong();
      if (accepted != eventCount) fail("HUMAN_ACTION_COUNT_MISMATCH");
      observer = capture.path("producerId").asText();
      sequence = frozen.sequence();
      lastTick = tick;
      previousCapture = capture;
      byTick.put(tick, frozen);
    }
    if (!previousCapture.path("terminal").asBoolean()
        || previousCapture.path("pendingCount").asLong() != 0) fail("HUMAN_TERMINAL_UNPROVEN");
    if (!byTick.containsKey(start) || !byTick.containsKey(end)) fail("HUMAN_RANGE_UNOBSERVED");
    List<ConvertedReplayAction> actions = new ArrayList<>();
    for (var frozen : all) {
      for (JsonNode observed : frozen.payload().path("humanCapture").path("actions")) {
        if (!eventIds.add(observed.path("eventId").asText())) fail("DUPLICATE_EVENT_ID");
        long sourceTick = observed.path("sourceTick").asLong();
        long beforeTick = observed.path("beforeSnapshotTick").asLong();
        var before = byTick.get(beforeTick);
        if (before == null
            || before.payload().path("playerState").path("animationId").asInt(-2) != -1
            || !before.payload().path("playerState").path("isIdle").isBoolean()
            || !before.payload().path("playerState").path("isIdle").asBoolean()
            || !before.payload().path("playerState").path("isMoving").isBoolean()
            || before.payload().path("playerState").path("isMoving").asBoolean())
          fail("HUMAN_IDLE_BASELINE_UNPROVEN");
        JsonNode target = observed.path("target");
        int matches = 0;
        for (JsonNode npc : before.payload().path("npcs")) {
          if (npc.path("stableId").equals(target.path("stableId"))
              && npc.path("index").asInt(-1) == target.path("clientIndex").asInt()
              && npc.path("fishingSpot").asBoolean()) matches++;
        }
        if (matches != 1) fail("HUMAN_PRIOR_TARGET_UNPROVEN");
        for (long waitingTick = sourceTick;
            waitingTick < observed.path("observationTick").asLong();
            waitingTick++) {
          var waiting = byTick.get(waitingTick);
          if (waiting == null
              || waiting.payload().path("humanCapture").path("pendingCount").asLong() != 1
              || !waiting.payload().path("humanCapture").path("actions").isEmpty())
            fail("HUMAN_PENDING_CONTINUITY_UNPROVEN");
          int waitingMatches = 0;
          for (JsonNode npc : waiting.payload().path("npcs")) {
            if (npc.path("stableId").equals(target.path("stableId"))
                && npc.path("index").asInt(-1) == target.path("clientIndex").asInt()
                && npc.path("fishingSpot").asBoolean()) waitingMatches++;
          }
          if (waitingMatches != 1) fail("HUMAN_PENDING_TARGET_UNPROVEN");
        }
        if (sourceTick < start || sourceTick > end) continue;
        if (beforeTick < start || observed.path("observationTick").asLong() > end)
          fail("HUMAN_TRANSITION_OUTSIDE_CURATION");
        if (actions.size() >= 256) fail("HUMAN_ACTION_LIMIT");
        RecordingAction action = new RecordingAction();
        action.setRecordingId(recording);
        action.setSessionId(session);
        action.setTick(sourceTick);
        action.setSequence(0);
        action.setActionType("INTERACT_NPC");
        action.setSource("MICRO6_CLIENT_OBSERVATION");
        action.setCapturedAt(observed.path("capturedAt").asText());
        action.setTarget(json.convertValue(target, new TypeReference<Map<String, Object>>() {}));
        var evidence =
            Map.of(
                "frameSequence",
                frozen.sequence().toString(),
                "frameDigest",
                frozen.digest(),
                "beforeFrameSequence",
                before.sequence().toString(),
                "beforeFrameDigest",
                before.digest(),
                "eventId",
                observed.path("eventId").asText());
        action.setArguments(Map.of("option", "Lure", "observationEvidence", evidence));
        actions.add(
            new ConvertedReplayAction(
                action,
                ActionProvenance.CAPTURED,
                "Client-observed idle-to-lure transition; operator-declared HUMAN origin; no"
                    + " dispatch or hardware-origin claim",
                null,
                null));
      }
    }
    if (actions.isEmpty()) fail("HUMAN_LURE_OBSERVATION_MISSING");
    var doc =
        json.createObjectNode()
            .put("converterVersion", "human-fishing-observation-converter-v1")
            .put("sessionId", session)
            .put("startTick", start)
            .put("endTick", end)
            .put("mode", mode.name());
    doc.set("actions", json.valueToTree(actions));
    // CERTIFIED describes complete captured conversion only, never oracle or worker qualification.
    return new ReplayActionConversionResult(
        session,
        start,
        end,
        mode,
        ConversionQuality.CERTIFIED,
        List.copyOf(actions),
        List.of(),
        List.of(),
        0,
        0,
        100.0,
        projection.sha256Hex(projection.canonicalJsonBytes(doc)));
  }

  private static void fail(String code) {
    throw new ProvenanceException(code);
  }
}
