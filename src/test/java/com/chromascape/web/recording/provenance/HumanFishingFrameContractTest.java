package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class HumanFishingFrameContractTest {
  static final ObjectMapper JSON = new ObjectMapper();

  static JsonNode fixture() throws Exception {
    try (var input =
        HumanFishingFrameContractTest.class.getResourceAsStream(
            "/human-fishing/authored-observer.json")) {
      return JSON.readTree(input);
    }
  }

  @Test
  void actualMicro6SerializerAgreesWithConsumer() throws Exception {
    var data = fixture();
    assertEquals("AUTHORED_OBSERVER_TEST", data.path("evidenceKind").asText());
    for (var frame : data.path("frames"))
      assertDoesNotThrow(() -> CanonicalTickFrame.validate(frame, data.path("context")));
  }

  @Test
  void rejectsRelabellingDispatchAndHumanOrigin() throws Exception {
    var data = fixture();
    ObjectNode frame = (ObjectNode) data.path("frames").get(1).deepCopy();
    ((ObjectNode) frame.path("semanticEvents").get(0)).put("dispatched", true);
    final var dispatched = frame;
    assertThrows(
        ProvenanceException.class,
        () -> HumanFishingFrameContract.validate(dispatched, data.path("context")));
    frame = (ObjectNode) data.path("frames").get(1).deepCopy();
    ((ObjectNode) frame.path("semanticEvents").get(0)).put("humanObserved", true);
    final var changed = frame;
    assertThrows(
        ProvenanceException.class,
        () -> HumanFishingFrameContract.validate(changed, data.path("context")));
  }

  @Test
  void rejectsConsumedClicksAndForgedTargets() throws Exception {
    var data = fixture();
    for (String field : new String[] {"menuConsumed", "target"}) {
      ObjectNode frame = (ObjectNode) data.path("frames").get(1).deepCopy();
      ObjectNode action = (ObjectNode) frame.path("humanCapture").path("actions").get(0);
      if (field.equals("menuConsumed")) action.put(field, true);
      else ((ObjectNode) action.path("target")).put("clientIndex", 99);
      assertThrows(
          ProvenanceException.class,
          () -> HumanFishingFrameContract.validate(frame, data.path("context")));
    }
  }

  @Test
  void refusesUnknownInventoryAsObservedZero() throws Exception {
    var data = fixture();
    ObjectNode frame = (ObjectNode) data.path("frames").get(0).deepCopy();
    ((ObjectNode) frame.path("semanticSnapshot"))
        .putArray("legalActionIds")
        .add("INTERACT_NPC_LURE");
    assertThrows(
        ProvenanceException.class,
        () -> HumanFishingFrameContract.validate(frame, data.path("context")));
  }

  @Test
  void oldFramesRemainOutsideHumanContract() {
    assertFalse(HumanFishingFrameContract.applies(JSON.createObjectNode().putNull("humanCapture")));
  }
}
