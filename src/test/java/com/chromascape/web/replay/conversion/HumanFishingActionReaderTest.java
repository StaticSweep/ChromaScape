package com.chromascape.web.replay.conversion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.integration.micro6.service.Micro6CanonicalHash;
import com.chromascape.web.recording.provenance.*;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HumanFishingActionReaderTest {
  final ObjectMapper json = new ObjectMapper();
  final HumanFishingActionReader reader =
      new HumanFishingActionReader(
          mock(DebugSessionRepository.class),
          mock(BehaviorRunContextService.class),
          mock(CanonicalFrameStore.class),
          json,
          new CanonicalProjectionService());

  JsonNode fixture() throws Exception {
    try (var stream = getClass().getResourceAsStream("/human-fishing/authored-observer.json")) {
      return json.readTree(stream);
    }
  }

  List<CanonicalTickFrame> frames(JsonNode data) {
    List<CanonicalTickFrame> result = new ArrayList<>();
    for (var frame : data.path("frames"))
      result.add(CanonicalTickFrame.validate(frame, data.path("context")));
    return result;
  }

  ReplayActionConversionResult convert(JsonNode data, long start, long end) {
    return reader.convertFrames(
        data.path("context").path("debugSessionId").asText(),
        "authored-recording",
        start,
        end,
        ConversionMode.CAPTURED_ONLY,
        data.path("context"),
        frames(data));
  }

  void rehash(ObjectNode frame) {
    frame.remove("contentHash");
    frame.put("contentHash", Micro6CanonicalHash.sha256WithPrefix(frame).substring(7));
  }

  @Test
  void deterministicObservedConversionIsDistinctFromBotDispatch() throws Exception {
    var data = fixture();
    var first = convert(data, 1, 3);
    var second = convert(data, 1, 3);
    assertEquals(first.conversionChecksum(), second.conversionChecksum());
    assertEquals(1, first.actions().size());
    assertEquals("MICRO6_CLIENT_OBSERVATION", first.actions().get(0).action().getSource());
    assertTrue(first.actions().get(0).action().getArguments().containsKey("observationEvidence"));
    assertTrue(first.gaps().isEmpty());
  }

  @Test
  void rejectsMissingBaselineAndCroppedTransition() throws Exception {
    var data = fixture();
    assertThrows(ProvenanceException.class, () -> convert(data, 2, 3));
    var list = frames(data);
    list.remove(0);
    assertThrows(
        ProvenanceException.class,
        () ->
            reader.convertFrames("s", "r", 2, 3, ConversionMode.AUTO, data.path("context"), list));
  }

  @Test
  void rejectsFrameReordering() throws Exception {
    var data = fixture();
    var list = frames(data);
    java.util.Collections.swap(list, 1, 2);
    assertThrows(
        ProvenanceException.class,
        () ->
            reader.convertFrames("s", "r", 1, 3, ConversionMode.AUTO, data.path("context"), list));
  }

  @Test
  void rejectsResealedNonIdleBaseline() throws Exception {
    var data = fixture();
    ObjectNode first = (ObjectNode) data.path("frames").get(0);
    ((ObjectNode) first.path("playerState")).put("animationId", 623);
    rehash(first);
    assertThrows(ProvenanceException.class, () -> convert(data, 1, 3));
  }

  @Test
  void rejectsObserverRestartAndMissingTerminal() throws Exception {
    for (String field : new String[] {"producerId", "terminal"}) {
      var data = fixture();
      ObjectNode last = (ObjectNode) data.path("frames").get(2);
      if (field.equals("producerId"))
        ((ObjectNode) last.path("humanCapture")).put(field, java.util.UUID.randomUUID().toString());
      else ((ObjectNode) last.path("humanCapture")).put(field, false);
      rehash(last);
      assertThrows(ProvenanceException.class, () -> convert(data, 1, 3));
    }
  }

  @Test
  void rejectsResealedInventoryDeltaAndMissingActionCount() throws Exception {
    for (String field : new String[] {"positiveDeltaFromStart", "acceptedIntents"}) {
      var data = fixture();
      ObjectNode last = (ObjectNode) data.path("frames").get(2);
      if (field.equals("positiveDeltaFromStart")) {
        ((ObjectNode) last.path("humanCapture").path("semanticSnapshot").path("inventory"))
            .put(field, 20);
        ((ObjectNode) last.path("semanticSnapshot").path("inventory")).put(field, 20);
      } else ((ObjectNode) last.path("humanCapture")).put(field, 0);
      rehash(last);
      assertThrows(ProvenanceException.class, () -> convert(data, 1, 3));
    }
  }
}
