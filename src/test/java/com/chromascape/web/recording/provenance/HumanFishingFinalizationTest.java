package com.chromascape.web.recording.provenance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class HumanFishingFinalizationTest {
  final ObjectMapper json = new ObjectMapper();

  @Test
  void onlyObservedTerminalWithNoPendingIntentClosesCleanly() throws Exception {
    assertEquals(0, close(true, 0).path("unknown").asInt());
    assertEquals(1, close(false, 0).path("unknown").asInt());
    assertEquals(1, close(true, 1).path("unknown").asInt());
  }

  @Test
  void oldQualityIsNeverRetrofittedAsHumanCoverage() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(String.class), eq("s")))
        .thenReturn(
            List.of(
                "{\"unknown\":1169,\"unsupportedFamilies\":[\"SCRIPT_INTENT\",\"TRANSITION_VERIFICATION\"]}"));
    new CanonicalFrameStore(jdbc, mock(BehaviorRunContextService.class), json)
        .close("s", "EXPLICIT_STOP");
    var quality = ArgumentCaptor.forClass(String.class);
    verify(jdbc).update(startsWith("UPDATE recording_capture_quality"), quality.capture(), eq("s"));
    assertEquals(1169, json.readTree(quality.getValue()).path("unknown").asInt());
  }

  com.fasterxml.jackson.databind.JsonNode close(boolean terminal, long pending) throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var quality =
        json.createObjectNode()
            .put("humanObserverId", "observer")
            .put("lastHumanTerminal", terminal)
            .put("lastHumanPending", pending)
            .put("unknown", 0);
    quality.putArray("unsupportedFamilies");
    when(jdbc.queryForList(anyString(), eq(String.class), eq("s")))
        .thenReturn(List.of(quality.toString()));
    new CanonicalFrameStore(jdbc, mock(BehaviorRunContextService.class), json)
        .close("s", "EXPLICIT_STOP");
    var captured = ArgumentCaptor.forClass(String.class);
    verify(jdbc)
        .update(startsWith("UPDATE recording_capture_quality"), captured.capture(), eq("s"));
    return json.readTree(captured.getValue());
  }
}
