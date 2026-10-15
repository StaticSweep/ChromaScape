package com.chromascape.web.debug.tickdebugger.service;

import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Additive, bounded evidence. Raw client events never become acknowledged script actions. */
public final class CaptureQualityContract {
  public static final String SCHEMA = "capture-quality/v1";
  private static final JsonSchema CONTRACT = loadContract();

  private static JsonSchema loadContract() {
    try (var in =
        CaptureQualityContract.class.getResourceAsStream(
            "/contracts/replay/capture-quality-v1.schema.json")) {
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
    } catch (Exception failure) {
      throw new IllegalStateException("Capture quality schema unavailable", failure);
    }
  }

  private CaptureQualityContract() {}

  public static JsonNode validate(TickBatchMessage batch, ObjectMapper mapper) {
    if (batch.getCaptureQuality() == null) {
      return null;
    }
    JsonNode quality = mapper.valueToTree(batch.getCaptureQuality());
    validate(quality, batch.getSessionId(), batch.getTick(), mapper);
    for (JsonNode e : quality.path("settlements")) {
      if (!"CONFIRMED".equals(e.path("status").asText())
          || e.path("observationTick").longValue() != batch.getTick()) {
        continue;
      }
      JsonNode after = e.path("stateEvidence").path("after"), target = e.path("target");
      JsonNode player = mapper.valueToTree(batch.getPlayerState());
      switch (e.path("actionType").asText()) {
        case "WALK_TO" -> require(
            after.path("x").asInt() == player.path("worldX").asInt(-1)
                && after.path("y").asInt() == player.path("worldY").asInt(-1)
                && after.path("plane").asInt() == player.path("plane").asInt(-1),
            "captured movement");
        case "INTERACT_INVENTORY_ITEM" -> {
          require(batch.getInventory() != null, "captured inventory");
          int count =
              batch.getInventory().stream()
                  .filter(
                      item ->
                          ((Number) item.get("itemId")).intValue() == target.path("itemId").asInt())
                  .mapToInt(item -> ((Number) item.get("quantity")).intValue())
                  .sum();
          require(after.path("quantity").asInt(-1) == count, "captured item mutation");
        }
        case "INTERACT_WIDGET" -> {
          require(player.path("prayersActive").isArray(), "captured prayers");
          boolean active = false;
          for (JsonNode p : player.path("prayersActive")) {
            if (p.asText().equals(target.path("prayer").asText())) {
              active = true;
            }
          }
          require(after.path("active").booleanValue() == active, "captured prayer mutation");
        }
        case "INTERACT_NPC" -> require(
            "NPC".equals(player.path("interacting").path("type").asText())
                && player.path("interacting").path("id").asInt(-1) == target.path("npcId").asInt()
                && player.path("interacting").path("index").asInt(-1)
                    == target.path("clientIndex").asInt()
                && batch.getNpcs() != null
                && batch.getNpcs().stream()
                    .anyMatch(
                        n ->
                            target.path("stableId").asText().equals(n.get("stableId"))
                                && ((Number) n.get("index")).intValue()
                                    == target.path("clientIndex").asInt()),
            "captured target");
        default -> throw new IllegalArgumentException("Invalid captureQuality action");
      }
    }
    return quality;
  }

  public static void validate(JsonNode q, String session, long tick, ObjectMapper mapper) {
    try {
      require(q.isObject() && SCHEMA.equals(q.path("schema").asText()), "schema");
      require(mapper.writeValueAsBytes(q).length <= 262144, "size");
      require(CONTRACT.validate(q).isEmpty(), "schema fields");
      require(
          session.equals(q.path("sessionId").asText())
              && q.path("tick").isIntegralNumber()
              && q.path("tick").longValue() == tick,
          "binding");
      require(id(q.path("producerId")), "producer");
      require(
          q.path("epochTick").isIntegralNumber()
              && q.path("epochTick").longValue() >= 0
              && q.path("epochTick").longValue() <= tick,
          "epoch");
      require(
          q.path("tickCapturedAtEpochMillis").isIntegralNumber()
              && q.path("epochCapturedAtEpochMillis").isIntegralNumber(),
          "timestamps");
      require(
          q.path("qualityCodes").isArray()
              && q.path("qualityCodes").size() <= 32
              && q.path("terminal").isBoolean()
              && q.path("pendingCount").canConvertToInt()
              && q.path("pendingCount").intValue() >= 0
              && q.path("pendingCount").intValue() <= 64,
          "quality");
      for (JsonNode code : q.path("qualityCodes")) {
        require(code.isTextual() && code.asText().matches("[A-Z_]{1,64}"), "quality code");
      }
      JsonNode clock = q.path("clock");
      require(
          Set.of("MEASURED", "UNAVAILABLE").contains(clock.path("status").asText()),
          "clock status");
      if ("MEASURED".equals(clock.path("status").asText())) {
        double uncertainty = clock.path("uncertaintyMillis").asDouble(Double.NaN);
        double offset = clock.path("serverOffsetMillis").asDouble(Double.NaN);
        long measured = clock.path("measuredAtEpochMillis").asLong(0);
        long capture = q.path("tickCapturedAtEpochMillis").longValue();
        require(
            Double.isFinite(offset)
                && Double.isFinite(uncertainty)
                && uncertainty >= 0
                && uncertainty <= 50
                && measured > 0
                && measured <= capture
                && capture - measured <= 30000,
            "clock sample");
      }
      require(
          q.path("rawEvents").isArray()
              && q.path("rawEvents").size() <= 128
              && q.path("settlements").isArray()
              && q.path("settlements").size() <= 128,
          "event bounds");
      Map<String, JsonNode> raw = new HashMap<>();
      for (JsonNode e : q.path("rawEvents")) {
        binding(e, q, tick);
        require(e.path("payload").isObject() && e.path("payload").size() == 4, "raw payload");
        TreeMap<String, Long> payload = new TreeMap<>();
        for (String key : Set.of("opcode", "identifier", "param0", "param1")) {
          require(e.path("payload").path(key).isIntegralNumber(), "raw operand");
          payload.put(key, e.path("payload").path(key).longValue());
        }
        String digest =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(
                            mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8)));
        require(digest.equals(e.path("payloadHash").asText()), "raw hash");
        require(raw.putIfAbsent(e.path("eventId").asText(), e) == null, "duplicate raw");
      }
      Set<String> settledIds = new java.util.HashSet<>();
      for (JsonNode e : q.path("settlements")) {
        binding(e, q, tick);
        require(settledIds.add(e.path("eventId").asText()), "duplicate settlement");
        JsonNode r = raw.get(e.path("eventId").asText());
        require(
            r != null
                && r.path("sequence").equals(e.path("sequence"))
                && r.path("sourceTick").equals(e.path("sourceTick")),
            "raw lineage");
        require(
            e.path("rawEventIds").isArray()
                && e.path("rawEventIds").size() == 1
                && e.path("eventId").asText().equals(e.path("rawEventIds").get(0).asText()),
            "raw reference");
        String status = e.path("status").asText();
        require(Set.of("CONFIRMED", "UNCONFIRMED", "AMBIGUOUS").contains(status), "status");
        require(
            e.path("settlementTick").isIntegralNumber()
                && e.path("observationTick").isIntegralNumber()
                && e.path("settlementTick").longValue() <= tick
                && e.path("settlementTick").equals(e.path("observationTick")),
            "observation");
        require(
            e.path("outcomeHorizonTick").isIntegralNumber()
                && e.path("outcomeHorizonTick").longValue() == e.path("sourceTick").longValue() + 4,
            "horizon");
        if ("CONFIRMED".equals(status)) {
          require(
              Set.of("INTERACT_NPC", "WALK_TO", "INTERACT_INVENTORY_ITEM", "INTERACT_WIDGET")
                  .contains(e.path("actionType").asText()),
              "action");
          require(
              e.path("settlementTick").longValue() >= e.path("sourceTick").longValue()
                  && e.path("settlementTick").longValue()
                      <= e.path("outcomeHorizonTick").longValue()
                  && "END_OF_TICK_MUTATION".equals(e.path("reason").asText())
                  && "AUTHORITATIVE_END_OF_TICK_MUTATION".equals(e.path("derivation").asText())
                  && e.path("confidence").asDouble() == 1.0
                  && e.path("target").isObject()
                  && !e.path("target").isEmpty(),
              "confirmation");
          require(validMutation(e), "state mutation");
        }
      }
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalArgumentException("Invalid captureQuality", failure);
    }
  }

  private static void binding(JsonNode e, JsonNode q, long tick) {
    require(
        id(e.path("eventId"))
            && e.path("producerId").equals(q.path("producerId"))
            && e.path("sessionId").equals(q.path("sessionId"))
            && "CLIENT_EVENT".equals(e.path("source").asText())
            && e.path("sequence").isIntegralNumber()
            && e.path("sequence").longValue() > 0
            && e.path("sourceTick").isIntegralNumber()
            && e.path("sourceTick").longValue() >= 0
            && e.path("sourceTick").longValue() <= tick + 1,
        "event binding");
  }

  private static boolean validMutation(JsonNode e) {
    JsonNode evidence = e.path("stateEvidence"),
        before = evidence.path("before"),
        after = evidence.path("after"),
        target = e.path("target");
    if (!evidence.path("beforeTick").isIntegralNumber()
        || !evidence.path("afterTick").equals(e.path("observationTick"))
        || evidence.path("beforeTick").longValue() >= e.path("sourceTick").longValue()) {
      return false;
    }
    return switch (e.path("actionType").asText()) {
      case "INTERACT_NPC" -> after.path("npcId").isIntegralNumber()
          && after.path("clientIndex").isIntegralNumber()
          && after.path("npcId").equals(target.path("npcId"))
          && after.path("clientIndex").equals(target.path("clientIndex"))
          && target.path("stableId").isTextual()
          && !before.equals(after);
      case "INTERACT_INVENTORY_ITEM" -> before.path("quantity").isIntegralNumber()
          && after.path("quantity").isIntegralNumber()
          && after.path("quantity").intValue() >= 0
          && after.path("quantity").intValue() < before.path("quantity").intValue()
          && target.path("itemId").isIntegralNumber()
          && target.path("slot").isIntegralNumber();
      case "INTERACT_WIDGET" -> before.path("active").isBoolean()
          && after.path("active").isBoolean()
          && !before.path("active").equals(after.path("active"))
          && target.path("prayer").isTextual();
      case "WALK_TO" -> validMovementIntent(e)
          && before.path("plane").equals(after.path("plane"))
          && after.path("plane").equals(target.path("plane"))
          && distance(after, target) < distance(before, target);
      default -> false;
    };
  }

  /** Historical WALK operands cannot prove intent, even when distance happens to decrease. */
  private static boolean validMovementIntent(JsonNode event) {
    JsonNode evidence = event.path("stateEvidence"), intent = evidence.path("movementIntent");
    if (!"SELECTED_SCENE_TILE_AND_CHANGED_LOCAL_DESTINATION".equals(intent.path("source").asText())
        || !"TOP_LEVEL_SCENE".equals(intent.path("coordinateSpace").asText())
        || !intent.path("previousDestinationPresent").isBoolean()) {
      return false;
    }
    for (String key :
        Set.of(
            "baseX",
            "baseY",
            "plane",
            "selectedSceneX",
            "selectedSceneY",
            "destinationSceneX",
            "destinationSceneY",
            "sourceTick",
            "observedTick")) {
      if (!intent.path(key).isIntegralNumber() || !intent.path(key).canConvertToInt()) {
        return false;
      }
    }
    int x = intent.path("selectedSceneX").intValue(), y = intent.path("selectedSceneY").intValue();
    if (!sceneCoordinate(x)
        || !sceneCoordinate(y)
        || intent.path("plane").intValue() < 0
        || intent.path("plane").intValue() > 3
        || intent.path("baseX").intValue() < 0
        || intent.path("baseY").intValue() < 0
        || !intent.path("selectedSceneX").equals(intent.path("destinationSceneX"))
        || !intent.path("selectedSceneY").equals(intent.path("destinationSceneY"))
        || !intent.path("sourceTick").equals(event.path("sourceTick"))
        || intent.path("observedTick").longValue() < event.path("sourceTick").longValue()
        || intent.path("observedTick").longValue() > event.path("settlementTick").longValue()) {
      return false;
    }
    if (intent.path("previousDestinationPresent").booleanValue()) {
      JsonNode oldX = intent.path("previousDestinationSceneX"),
          oldY = intent.path("previousDestinationSceneY");
      if (!oldX.isIntegralNumber()
          || !oldY.isIntegralNumber()
          || !oldX.canConvertToInt()
          || !oldY.canConvertToInt()
          || !sceneCoordinate(oldX.intValue())
          || !sceneCoordinate(oldY.intValue())
          || oldX.intValue() == x && oldY.intValue() == y) {
        return false;
      }
    } else if (intent.has("previousDestinationSceneX") || intent.has("previousDestinationSceneY")) {
      return false;
    }
    for (JsonNode point :
        List.of(event.path("target"), evidence.path("before"), evidence.path("after"))) {
      if (!point.path("x").isIntegralNumber()
          || !point.path("y").isIntegralNumber()
          || !point.path("plane").equals(intent.path("plane"))
          || !sceneCoordinate(point.path("x").asLong() - intent.path("baseX").asLong())
          || !sceneCoordinate(point.path("y").asLong() - intent.path("baseY").asLong())) {
        return false;
      }
    }
    return event.path("target").path("x").asLong() == intent.path("baseX").asLong() + x
        && event.path("target").path("y").asLong() == intent.path("baseY").asLong() + y;
  }

  private static boolean sceneCoordinate(long coordinate) {
    return coordinate >= 0 && coordinate < 104;
  }

  private static long distance(JsonNode point, JsonNode target) {
    return Math.max(
        Math.abs(point.path("x").asLong() - target.path("x").asLong()),
        Math.abs(point.path("y").asLong() - target.path("y").asLong()));
  }

  private static boolean id(JsonNode n) {
    return n.isTextual() && n.asText().matches("[a-zA-Z0-9-]{1,64}");
  }

  private static void require(boolean condition, String field) {
    if (!condition) {
      throw new IllegalArgumentException("Invalid captureQuality " + field);
    }
  }
}
