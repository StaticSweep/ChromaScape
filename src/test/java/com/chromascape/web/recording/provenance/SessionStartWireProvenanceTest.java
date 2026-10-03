package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.*;

import com.chromascape.web.debug.tickdebugger.dto.SessionStartRequest;
import com.chromascape.web.recording.dto.RecordingStartRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Consumer wire fixtures are independent of the unrun native producer tests. */
class SessionStartWireProvenanceTest {
  @Test
  void additiveExecutedSettingsAndNegotiationAreReadFromWire() throws Exception {
    var json = new ObjectMapper();
    var request =
        json.readValue(
            "{\"accountName\":\"actual-account\",\"scriptName\":\"Fishing\",\"taskKey\":\"FISHING_TRAINING\",\"taskArgs\":{\"fish\":\"SHRIMP\"},\"supportedTickBatchVersions\":[2,3],\"supportedAckVersions\":[1]}",
            SessionStartRequest.class);
    assertEquals("SHRIMP", request.getTaskArgs().path("fish").asText());
    assertEquals(java.util.List.of(2, 3), request.getSupportedTickBatchVersions());
    assertEquals(java.util.List.of(1), request.getSupportedAckVersions());
    assertNotNull(
        RecordingProvenance.capture(request.getTaskKey(), request.getTaskArgs()).settingsHash());
  }

  @Test
  void typedManualUnknownAndKnownEmptyStayDistinct() throws Exception {
    var json = new ObjectMapper();
    var unknown = json.readValue("{\"taskKey\":\"FISHING_TRAINING\"}", RecordingStartRequest.class);
    var empty =
        json.readValue(
            "{\"taskKey\":\"FISHING_TRAINING\",\"taskArgs\":{}}", RecordingStartRequest.class);
    assertNull(RecordingProvenance.capture(unknown.taskKey(), unknown.taskArgs()).settingsHash());
    assertNotNull(RecordingProvenance.capture(empty.taskKey(), empty.taskArgs()).settingsHash());
  }
}
