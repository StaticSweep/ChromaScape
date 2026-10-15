package com.chromascape.web.aiplayer.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.chromascape.web.debug.tickdebugger.service.CaptureQualityContract;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import com.chromascape.web.recording.service.MeasuredVideoIndex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Actual sanitized capture examples; database/media-byte verification is separate live evidence.
 */
class CombatRealCaptureContractTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode resource(String name) throws Exception {
    try (var in = getClass().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      return mapper.readTree(in);
    }
  }

  private JsonNode fixture() throws Exception {
    return resource("/replay/contracts/combat-real-capture-2b0178ae-v1.json");
  }

  private com.networknt.schema.JsonSchema rowSchema() throws Exception {
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(resource("/contracts/replay/combat-tick-row-v1.schema.json"));
  }

  @Test
  void standaloneCombatContractKeepsCanonicalCaptureQualityDefinition() throws Exception {
    ObjectNode canonical =
        (ObjectNode) resource("/contracts/replay/capture-quality-v1.schema.json");
    canonical.remove(List.of("$schema", "$id", "title"));
    JsonNode embedded =
        resource("/contracts/replay/combat-tick-row-v1.schema.json")
            .path("$defs")
            .path("captureQuality");
    assertThat(embedded).isEqualTo(canonical);
  }

  @Test
  void realCombatExamplesSurviveIngestionProjectionSchemaAndCoverage() throws Exception {
    JsonNode fixture = fixture();
    String sid = fixture.path("sessionId").asText();
    var sessions = mock(DebugSessionService.class);
    when(sessions.getSessionForIngestion(sid))
        .thenReturn(Optional.of(new DebugSession(sid, "fixture", "combat", null, 509L)));
    var snapshots = mock(TickSnapshotRepository.class);
    when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var ingestion =
        new TickStreamIngestionService(
            sessions,
            snapshots,
            mock(TickLogRepository.class),
            mock(TickOverlayRepository.class),
            mock(TimelineMarkerRepository.class),
            mapper,
            null,
            null);
    var schema = rowSchema();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (JsonNode payload : fixture.path("snapshots")) {
      TickBatchMessage batch = mapper.treeToValue(payload, TickBatchMessage.class);
      CaptureQualityContract.validate(batch, mapper);
      ingestion.processTickBatch(batch);
    }
    var captured = ArgumentCaptor.forClass(TickSnapshot.class);
    verify(snapshots, org.mockito.Mockito.times(8)).save(captured.capture());
    int resolved = 0;
    for (TickSnapshot snapshot : captured.getAllValues()) {
      // Exercise the persisted JSON representation as well as the actual ingestion mapping.
      snapshot.setCaptureQuality(
          mapper.readTree(mapper.writeValueAsBytes(snapshot.getCaptureQuality())));
      long tick = snapshot.getTick();
      var start = mapping(fixture, tick);
      var end = mapping(fixture, tick + 1);
      var sync = new CombatTickRowService.VideoSync(509, 30, start, end);
      Map<String, Object> row = CombatTickRowService.build(snapshot, null, null, sync);
      assertThat(schema.validate(mapper.valueToTree(row))).as("tick %s", tick).isEmpty();
      assertThat((List<?>) row.get("deferred")).isEmpty();
      assertThat(((Map<?, ?>) row.get("video")).get("frameStart")).isNotNull();
      if (Boolean.TRUE.equals(((Map<?, ?>) row.get("target")).get("resolved"))) resolved++;
      rows.add(row);
    }
    assertThat(resolved).isPositive();
    var coverage = CombatRecordingCoverage.summarize(sid, rows);
    assertThat(coverage.distinctTicks()).isEqualTo(8);
    assertThat(coverage.invalidLabels()).isEqualTo(2);
    assertThat(coverage.actionCounts())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("INTERACT_NPC", 1L, "INTERACT_WIDGET", 1L, "WALK_TO", 4L));
    assertThat(coverage.deferredTickCounts()).isEmpty();
    rows.addAll(List.copyOf(rows));
    assertThat(CombatRecordingCoverage.summarize(sid, rows)).isEqualTo(coverage);
  }

  @Test
  void realMovementProofRemainsStrictAndRawInputsCannotReplaceTheLabel() throws Exception {
    var fixture = fixture();
    var schema = rowSchema();
    int walks = 0;
    for (JsonNode payload : fixture.path("snapshots")) {
      ObjectNode quality = (ObjectNode) payload.path("captureQuality").deepCopy();
      var batch = mapper.treeToValue(payload, TickBatchMessage.class);
      var row =
          CombatTickRowService.build(batch, null, null, CombatTickRowService.VideoSync.absent(509));
      if (!"WALK_TO".equals(((Map<?, ?>) row.get("label")).get("canonicalType"))) continue;
      CombatTickRowService.requireLabelSafe(row);
      ObjectNode state = (ObjectNode) quality.path("settlements").get(0).path("stateEvidence");
      ((ObjectNode) state.path("movementIntent")).put("unrecognized", true);
      ObjectNode invalid = mapper.valueToTree(row);
      invalid.set("captureQuality", quality);
      assertThat(schema.validate(invalid)).isNotEmpty();
      ((ObjectNode) state.path("movementIntent")).remove("unrecognized");
      state.remove("movementIntent");
      row.put("captureQuality", mapper.convertValue(quality, Map.class));
      assertThatThrownBy(() -> CombatTickRowService.requireLabelSafe(row))
          .isInstanceOf(IllegalArgumentException.class);
      ((Map<String, Object>) row.get("label")).put("mouseX", 123);
      assertThatThrownBy(() -> CombatTickRowService.requireLabelSafe(row))
          .isInstanceOf(IllegalArgumentException.class);
      walks++;
    }
    assertThat(walks).isEqualTo(4);
  }

  private MeasuredVideoIndex.Mapping mapping(JsonNode fixture, long tick) throws Exception {
    return mapper.treeToValue(
        fixture.path("videoMeasurements").path(Long.toString(tick)),
        MeasuredVideoIndex.Mapping.class);
  }
}
