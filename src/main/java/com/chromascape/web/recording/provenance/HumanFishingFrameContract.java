package com.chromascape.web.recording.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Closed read-only manual lure observation contract, independent of bot dispatch evidence. */
public final class HumanFishingFrameContract {
  public static final String PRODUCER = "micro6-human-fishing-observer-v1";
  public static final String ADAPTER = "human-fishing-observation-adapter-v1";

  private HumanFishingFrameContract() {}

  public static boolean applies(JsonNode frame) {
    return frame.hasNonNull("humanCapture")
        || PRODUCER.equals(frame.path("quality").path("producerVersion").asText());
  }

  public static void validate(JsonNode frame, JsonNode context) {
    JsonNode capture = frame.path("humanCapture"), quality = frame.path("quality");
    if (!"HUMAN".equals(context.path("dataOrigin").asText())
        || !"FISHING_TRAINING".equals(context.path("taskKey").asText())
        || !"TROUT".equals(context.path("settings").path("targetFish").asText())
        || !"human-fishing-observation/v1".equals(capture.path("schema").asText())
        || !PRODUCER.equals(quality.path("producerVersion").asText())
        || !ADAPTER.equals(quality.path("adapterVersion").asText())
        || !frame.path("sessionId").equals(capture.path("sessionId"))
        || !frame.path("tick").equals(capture.path("tick")))
      fail("HUMAN_OBSERVATION_CONTEXT_MISMATCH");
    try {
      UUID.fromString(capture.path("producerId").asText());
    } catch (Exception invalid) {
      fail("HUMAN_OBSERVER_ID_INVALID");
    }
    for (String field :
        new String[] {"pendingCount", "observedCallbacks", "acceptedIntents", "dropped"}) {
      if (!capture.path(field).isIntegralNumber()
          || !capture.path(field).canConvertToLong()
          || capture.path(field).asLong() < 0) fail("HUMAN_OBSERVATION_COUNTER_INVALID");
    }
    if (capture.path("pendingCount").asLong() > 64
        || !capture.path("issues").isArray()
        || capture.path("issues").size() > 128
        || !capture.path("actions").isArray()
        || capture.path("actions").size() > 64
        || !capture.path("terminal").isBoolean()) fail("HUMAN_OBSERVATION_LIMIT");
    if (quality.path("unknown").asLong() != capture.path("issues").size()
        || quality.path("dropped").asLong() != capture.path("dropped").asLong()
        || !quality.path("unsupportedFamilies").equals(capture.path("issues"))
        || frame.path("semanticEvents").size() != capture.path("actions").size())
      fail("HUMAN_OBSERVATION_COUNTER_MISMATCH");
    if (capture.path("acceptedIntents").asLong() > capture.path("observedCallbacks").asLong())
      fail("HUMAN_OBSERVATION_COUNTER_INVALID");
    if (capture.path("issues").isEmpty()
        && capture.path("dropped").asLong() == 0
        && capture.path("observedCallbacks").asLong()
            != capture.path("acceptedIntents").asLong() + capture.path("pendingCount").asLong())
      fail("HUMAN_UNACCOUNTED_CALLBACK");
    if (capture.path("terminal").asBoolean()
        && capture.path("pendingCount").asLong() != 0
        && capture.path("issues").isEmpty()) fail("HUMAN_OBSERVATION_UNRESOLVED");
    if (!frame.path("semanticSnapshot").equals(capture.path("semanticSnapshot")))
      fail("HUMAN_SNAPSHOT_MISMATCH");
    JsonNode snapshot = frame.path("semanticSnapshot");
    validateInventory(frame, snapshot, capture.path("issues").isEmpty());
    boolean lureAllowed =
        snapshot.path("visibleTarget").asBoolean() && snapshot.path("requiredSupplies").asBoolean();
    JsonNode ids = snapshot.path("legalActionIds");
    if (!ids.isArray()
        || ids.size() != (lureAllowed ? 2 : 1)
        || !"WAIT".equals(ids.path(0).asText())
        || lureAllowed && !"INTERACT_NPC_LURE".equals(ids.path(1).asText()))
      fail("HUMAN_LEGAL_MASK_INVALID");
    if (frame.has("inputs")) fail("HUMAN_RAW_INPUT_IN_CANONICAL_FRAME");
    for (JsonNode npc : frame.path("npcs"))
      if (npc.has("interactingPlayerName")) fail("HUMAN_IDENTITY_NOT_REDACTED");
    Set<String> eventIds = new HashSet<>();
    for (int i = 0; i < capture.path("actions").size(); i++) {
      JsonNode action = capture.path("actions").get(i), event = frame.path("semanticEvents").get(i);
      validateAction(action, frame);
      if (!eventIds.add(action.path("eventId").asText())
          || !event.path("observation").equals(action)
          || !event.path("eventId").equals(action.path("eventId"))
          || !event.path("sourceTick").equals(action.path("sourceTick"))
          || !"CLIENT_EVENT".equals(event.path("source").asText())
          || !"INTERACT_NPC_LURE".equals(event.path("kind").asText())
          || !"FISHING_ANIMATION_STARTED".equals(event.path("reasonCode").asText())
          || !"SUCCEEDED".equals(event.path("outcome").asText())
          || !event.path("dispatched").isBoolean()
          || event.path("dispatched").asBoolean()
          || event.path("humanObserved").asBoolean()) fail("HUMAN_OBSERVATION_RELABELLED");
    }
  }

  public static void validateAction(JsonNode action, JsonNode frame) {
    if (!"client-observed-fishing-action/v1".equals(action.path("schema").asText())
        || !"CLIENT_EVENT".equals(action.path("source").asText())
        || !"INTERACT_NPC_LURE".equals(action.path("kind").asText())
        || !action.path("sourceTick").isIntegralNumber()
        || !action.path("beforeSnapshotTick").isIntegralNumber()
        || action.path("beforeSnapshotTick").asLong() != action.path("sourceTick").asLong() - 1
        || !frame.path("tick").equals(action.path("observationTick"))
        || action.path("sourceTick").asLong() < frame.path("tick").asLong() - 1
        || action.path("sourceTick").asLong() > frame.path("tick").asLong()
        || action.path("beforeAnimationId").asInt(-2) != -1
        || action.path("afterAnimationId").asInt(-1) != 623
        || frame.path("playerState").path("animationId").asInt(-1) != 623
        || !action.path("menuConsumed").isBoolean()
        || action.path("menuConsumed").asBoolean()
        || !action.path("dispatchCorrelationId").isIntegralNumber()
        || action.path("dispatchCorrelationId").asLong(-1) != 0) fail("HUMAN_TRANSITION_UNPROVEN");
    try {
      UUID.fromString(action.path("eventId").asText());
      java.time.Instant.parse(action.path("capturedAt").asText());
    } catch (Exception invalid) {
      fail("HUMAN_ACTION_ID_INVALID");
    }
    JsonNode target = action.path("target");
    if (!"NPC".equals(target.path("kind").asText())
        || !"lure".equals(target.path("spotType").asText())
        || !"river_lure_bait".equals(target.path("spotId").asText())) fail("HUMAN_TARGET_UNPROVEN");
    for (String field :
        new String[] {"npcId", "npcTypeId", "clientIndex", "x", "y", "plane", "size"})
      if (!target.path(field).isIntegralNumber() || !target.path(field).canConvertToInt())
        fail("HUMAN_TARGET_UNPROVEN");
    if (target.path("npcId").asInt() <= 0
        || target.path("npcTypeId").asInt() <= 0
        || target.path("clientIndex").asInt() < 0
        || target.path("size").asInt() < 1
        || target.path("plane").asInt() < 0
        || target.path("plane").asInt() > 3) fail("HUMAN_TARGET_UNPROVEN");
    String expected =
        "npc:"
            + target.path("npcId").asInt()
            + ":"
            + target.path("x").asInt()
            + ":"
            + target.path("y").asInt()
            + ":"
            + target.path("plane").asInt();
    if (!expected.equals(target.path("stableId").asText())) fail("HUMAN_TARGET_UNPROVEN");
    int matches = 0;
    for (JsonNode npc : frame.path("npcs")) {
      if (expected.equals(npc.path("stableId").asText())) {
        matches++;
        if (npc.path("npcId").asInt(-1) != target.path("npcId").asInt()
            || npc.path("index").asInt(-1) != target.path("clientIndex").asInt()
            || !npc.path("fishingSpot").asBoolean()) fail("HUMAN_TARGET_UNPROVEN");
      }
    }
    if (matches != 1) fail("HUMAN_TARGET_AMBIGUOUS");
  }

  private static void fail(String code) {
    throw new ProvenanceException(code);
  }

  private static void validateInventory(JsonNode frame, JsonNode snapshot, boolean complete) {
    if (!complete) return;
    if (!frame.path("inventory").isArray()) fail("HUMAN_INVENTORY_UNOBSERVED");
    Set<Integer> slots = new HashSet<>();
    long total = 0;
    int occupied = 0;
    boolean rod = false, feathers = false;
    for (JsonNode item : frame.path("inventory")) {
      for (String key : new String[] {"slot", "itemId", "quantity"})
        if (!item.path(key).isIntegralNumber() || !item.path(key).canConvertToInt())
          fail("HUMAN_INVENTORY_UNOBSERVED");
      int slot = item.path("slot").asInt(),
          id = item.path("itemId").asInt(),
          quantity = item.path("quantity").asInt();
      if (slot < 0 || slot >= 28 || !slots.add(slot) || quantity < 0 || id <= 0 && quantity > 0)
        fail("HUMAN_INVENTORY_UNOBSERVED");
      if (id > 0 && quantity > 0) {
        total += quantity;
        occupied++;
        if (id == 309) rod = true;
        if (id == 314) feathers = true;
      }
    }
    JsonNode inventory = snapshot.path("inventory");
    if (!inventory.path("occupiedSlots").isIntegralNumber()
        || inventory.path("occupiedSlots").asLong() != occupied
        || !inventory.path("totalQuantity").isIntegralNumber()
        || inventory.path("totalQuantity").asLong() != total
        || !snapshot.path("requiredSupplies").isBoolean()
        || snapshot.path("requiredSupplies").asBoolean() != (rod && feathers))
      fail("HUMAN_INVENTORY_MISMATCH");
  }
}
