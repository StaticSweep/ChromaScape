package com.chromascape.web.debug.tickdebugger.service;

import static org.assertj.core.api.Assertions.*;

import com.chromascape.web.aiplayer.combat.CombatTickRowService;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaptureQualityContractTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void actualCurrentClientGsonFixtureSurvivesServerSchemaAndMutationValidation() throws Exception {
    try (var in =
        getClass()
            .getResourceAsStream("/replay/contracts/capture-quality-current-serializer-v1.json")) {
      var q = mapper.readTree(in);
      TickBatchMessage batch =
          mapper.readValue(
              "{\"sessionId\":\"session\",\"tick\":3,\"captureQuality\":" + q + "}",
              TickBatchMessage.class);
      batch.setInventory(List.of(Map.of("itemId", 315, "slot", 0, "quantity", 1)));
      assertThat(CaptureQualityContract.validate(batch, mapper)).isEqualTo(q);
      assertThat(q.path("clock").path("status").asText()).isEqualTo("UNAVAILABLE");
    }
  }

  private ObjectNode fixture() throws Exception {
    try (var in =
        getClass().getResourceAsStream("/replay/contracts/capture-quality-simulated-v1.json")) {
      return (ObjectNode) mapper.readTree(in);
    }
  }

  @Test
  void additiveDtoKeepsImmutableEvidenceAndConfirmedClientEventLabel() throws Exception {
    ObjectNode q = fixture();
    TickBatchMessage batch =
        mapper.readValue(
            "{\"sessionId\":\"session\",\"tick\":3,\"captureQuality\":" + q + "}",
            TickBatchMessage.class);
    batch.setInventory(List.of(Map.of("itemId", 315, "slot", 0, "quantity", 1)));
    assertThat(CaptureQualityContract.validate(batch, mapper)).isEqualTo(q);
    assertThat(mapper.readTree(mapper.writeValueAsString(batch)).get("captureQuality"))
        .isEqualTo(q);
    TickSnapshot snapshot =
        new TickSnapshot("session", 3L, java.time.LocalDateTime.now(), mapper.createObjectNode());
    snapshot.setCaptureQuality(q);
    Map<String, Object> row =
        CombatTickRowService.build(snapshot, null, null, CombatTickRowService.VideoSync.absent(0));
    CombatTickRowService.requireLabelSafe(row);
    assertThat(((Map<?, ?>) row.get("label")).get("provenance")).isEqualTo("CLIENT_EVENT_SETTLED");
    assertThat(
            mapper
                .readValue("{\"sessionId\":\"session\",\"tick\":3}", TickBatchMessage.class)
                .getCaptureQuality())
        .isNull();
  }

  @Test
  void wrongBindingsHashesFutureExpiredAndUnmutatedEvidenceFailClosed() throws Exception {
    for (String field : List.of("sessionId", "producerId")) {
      var q = fixture();
      ((ObjectNode) q.path("settlements").get(0)).put(field, "other");
      assertThatThrownBy(() -> CaptureQualityContract.validate(q, "session", 3, mapper))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var hashQ = fixture();
    ((ObjectNode) hashQ.path("rawEvents").get(0).path("payload")).put("opcode", 999);
    assertThatThrownBy(() -> CaptureQualityContract.validate(hashQ, "session", 3, mapper))
        .hasMessageContaining("raw hash");
    var q = fixture();
    ((ObjectNode) q.path("settlements").get(0)).put("settlementTick", 7).put("observationTick", 7);
    final var expired = q;
    assertThatThrownBy(() -> CaptureQualityContract.validate(expired, "session", 3, mapper))
        .isInstanceOf(IllegalArgumentException.class);
    q = fixture();
    ((ObjectNode) q.path("settlements").get(0).path("stateEvidence").path("after"))
        .put("quantity", 2);
    final var unchanged = q;
    assertThatThrownBy(() -> CaptureQualityContract.validate(unchanged, "session", 3, mapper))
        .hasMessageContaining("state mutation");
  }

  @Test
  void uncalibratedOrAmbiguousRowsNeverBecomeLabelsAndReportCountsAreDeduplicated()
      throws Exception {
    var q = fixture();
    TickSnapshot s =
        new TickSnapshot("session", 3L, java.time.LocalDateTime.now(), mapper.createObjectNode());
    s.setCaptureQuality(q);
    var report = CaptureQualityReport.summarize("session", List.of(s), Map.of());
    assertThat(report)
        .containsEntry("rawEvents", 1)
        .containsEntry("settledEvents", 1)
        .containsEntry("acceptanceVerified", false);
    q.withArray("qualityCodes").add("TICK_GAP");
    var row = CombatTickRowService.build(s, null, null, CombatTickRowService.VideoSync.absent(0));
    assertThatThrownBy(() -> CombatTickRowService.requireLabelSafe(row))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
