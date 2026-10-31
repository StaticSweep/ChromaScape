package com.chromascape.web.recording.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/** Versioned nullable semantic observation from the same immutable recorded frame. */
public final class CanonicalObservation {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final ObjectNode observation;
  private final List<JsonNode> featureVector;

  private CanonicalObservation(ObjectNode observation, List<JsonNode> features) {
    this.observation = observation.deepCopy();
    this.featureVector = features.stream().<JsonNode>map(node -> node.deepCopy()).toList();
  }

  public JsonNode payload() {
    return observation.deepCopy();
  }

  public List<JsonNode> featureVector() {
    return featureVector.stream().<JsonNode>map(node -> node.deepCopy()).toList();
  }

  /** Legacy prototype adapter accepts only fully known frozen semantic features. */
  public static com.chromascape.web.aiplayer.prototype.FishingState knownFishingState(
      JsonNode observation) {
    JsonNode values = observation.path("fishingState");
    ObjectNode nested = JSON.createObjectNode();
    for (JsonNode field :
        BehaviorSemanticContract.definition().path("observation").path("fields")) {
      String id = field.path("id").asText();
      JsonNode value = values.path(id);
      if (value.isMissingNode() || value.isNull())
        throw new ProvenanceException("OBSERVATION_INCOMPLETE");
      String[] parts = id.split("\\.");
      if (parts.length == 2) nested.withObject(parts[0]).set(parts[1], value.deepCopy());
      else nested.set(id, value.deepCopy());
    }
    try {
      return JSON.treeToValue(nested, com.chromascape.web.aiplayer.prototype.FishingState.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new ProvenanceException("INVALID_OBSERVATION");
    }
  }

  public static CanonicalObservation from(CanonicalTickFrame frame) {
    JsonNode input = frame.payload();
    ObjectNode values = JSON.createObjectNode();
    List<JsonNode> vector = new ArrayList<>();
    // Artifact order is the sole owner of feature IDs/order/dimensions. Missing stays JSON null.
    for (JsonNode field :
        BehaviorSemanticContract.definition().path("observation").path("fields")) {
      String id = field.path("id").asText();
      JsonNode value = JSON.nullNode();
      if (id.startsWith("player.")) {
        String key = id.substring(7);
        JsonNode player = input.path("playerState");
        if (player.has(key)) value = player.get(key);
        else if (key.equals("x")) value = player.path("worldX");
        else if (key.equals("y")) value = player.path("worldY");
        else if (key.equals("moving")) value = player.path("isMoving");
        else if (key.equals("idle")) value = player.path("isIdle");
        if (value.isMissingNode()) value = JSON.nullNode();
        if ((key.equals("moving") || key.equals("idle")) && !value.isBoolean())
          value = JSON.nullNode();
        if (!(key.equals("moving") || key.equals("idle")) && !value.isIntegralNumber())
          value = JSON.nullNode();
      } else if (id.startsWith("inventory.")) {
        // Derived inventory aggregates must be supplied from the frozen frame or remain unknown.
        value = input.path("semanticSnapshot").path("inventory").path(id.substring(10));
        if (value.isMissingNode()) value = JSON.nullNode();
      } else if (id.equals("nearbyNpcs")) {
        value = input.path("semanticSnapshot").path("nearbyNpcs");
        if (value.isMissingNode()) value = JSON.nullNode();
      }
      values.set(id, value.deepCopy());
      if (field.path("dimension").isIntegralNumber()) vector.add(value.deepCopy());
    }
    ObjectNode out =
        JSON.createObjectNode()
            .put("schemaVersion", 1)
            .put("sessionId", input.path("sessionId").asText())
            .put("sourceTick", input.path("tick").asLong())
            .put("frameDigest", frame.digest())
            .put("semanticContractDigest", BehaviorSemanticContract.digest());
    out.set("fishingState", values);
    var mask = out.putArray("legalMask");
    boolean targetKnown = input.path("semanticSnapshot").path("visibleTarget").isBoolean();
    boolean suppliesKnown = input.path("semanticSnapshot").path("requiredSupplies").isBoolean();
    for (String id : BehaviorSemanticContract.actionIds())
      mask.add(
          HumanFishingFrameContract.applies(input)
              ? explicitHumanLegal(input.path("semanticSnapshot").path("legalActionIds"), id)
              : id.equals("WAIT")
                  || targetKnown
                      && suppliesKnown
                      && input.path("semanticSnapshot").path("visibleTarget").asBoolean()
                      && input.path("semanticSnapshot").path("requiredSupplies").asBoolean());
    return new CanonicalObservation(out, vector);
  }

  private static boolean explicitHumanLegal(JsonNode ids, String id) {
    if (!ids.isArray()) throw new ProvenanceException("HUMAN_LEGAL_MASK_MISSING");
    for (JsonNode allowed : ids)
      if (allowed.isTextual() && id.equals(allowed.asText())) return true;
    return false;
  }
}
