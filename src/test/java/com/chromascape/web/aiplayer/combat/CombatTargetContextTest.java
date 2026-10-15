package com.chromascape.web.aiplayer.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CombatTargetContextTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void realPartialCaptureReportsMissingNpcContextWithoutInventingLabels() throws Exception {
    JsonNode fixture;
    try (InputStream input =
        getClass().getResourceAsStream("/replay/contracts/combat-missing-npc-diagnostic-v1.json")) {
      assertThat(input).isNotNull();
      fixture = mapper.readTree(input);
    }
    assertThat(fixture.path("provenance").path("status").asText())
        .isEqualTo("PARTIAL_DIAGNOSTIC_NOT_ACCEPTED");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (JsonNode captured : fixture.path("snapshots")) {
      TickSnapshot snapshot =
          new TickSnapshot(
              "sanitized-partial",
              captured.path("tick").asLong(),
              LocalDateTime.parse(captured.path("timestamp").asText()),
              captured.path("playerState"));
      snapshot.setNpcs(captured.path("npcs"));
      snapshot.setInputs(captured.path("inputs"));
      Map<String, Object> row = CombatTickRowService.build(snapshot, null, null, null);
      rows.add(row);
      assertThat(target(row)).containsEntry("resolved", false).containsEntry("targetId", null);
      assertThatThrownBy(() -> CombatTickRowService.requireLabelSafe(row))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(deferred(rows.get(0))).contains("target_context");
    assertThat(deferred(rows.get(1))).doesNotContain("target_context");
    var coverage = CombatRecordingCoverage.summarize("sanitized-partial", rows);
    assertThat(coverage.distinctTicks()).isEqualTo(2);
    assertThat(coverage.invalidLabels()).isEqualTo(2);
    assertThat(coverage.actionCounts()).isEmpty();
    assertThat(coverage.deferredTickCounts()).containsEntry("target_context", 1L);
  }

  @Test
  void playerInteractionIndexCannotResolveToAnNpc() throws Exception {
    TickSnapshot snapshot = snapshot("PLAYER");
    Map<String, Object> row = CombatTickRowService.build(snapshot, null, null, null);
    assertThat(target(row)).containsEntry("resolved", false).containsEntry("targetId", null);
    assertThat(deferred(row)).doesNotContain("target_context");
  }

  @Test
  void resolvedNpcDoesNotRequireUnavailableAbsoluteHealth() throws Exception {
    TickSnapshot snapshot = snapshot("NPC");
    Map<String, Object> row = CombatTickRowService.build(snapshot, null, null, null);
    assertThat(target(row))
        .containsEntry("resolved", true)
        .containsEntry("targetId", 3023)
        .containsEntry("targetHp", null)
        .containsEntry("targetHealthRatio", 10);
    assertThat(deferred(row)).doesNotContain("target_context");
  }

  private TickSnapshot snapshot(String type) throws Exception {
    TickSnapshot snapshot =
        new TickSnapshot(
            "test",
            1L,
            LocalDateTime.of(2026, 10, 12, 12, 0),
            mapper.readTree("{\"interacting\":{\"type\":\"" + type + "\",\"index\":7}}"));
    snapshot.setNpcs(mapper.readTree("[{\"id\":3023,\"index\":7,\"healthRatio\":10}]"));
    return snapshot;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> target(Map<String, Object> row) {
    return (Map<String, Object>) row.get("target");
  }

  @SuppressWarnings("unchecked")
  private List<String> deferred(Map<String, Object> row) {
    return (List<String>) row.get("deferred");
  }
}
