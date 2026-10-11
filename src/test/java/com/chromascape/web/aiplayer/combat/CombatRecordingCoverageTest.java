package com.chromascape.web.aiplayer.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CombatRecordingCoverageTest {
  private Map<String, Object> row(long tick, String type, List<String> deferred) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("schema", CombatTickRowService.SCHEMA);
    row.put("tick_id", tick);
    row.put("deferred", deferred);
    row.put(
        "label",
        Map.of(
            "canonicalType",
            type,
            "sourceTick",
            tick,
            "sequence",
            0,
            "provenance",
            "MICRO6_SEMANTIC_DISPATCH",
            "unmapped",
            false));
    return row;
  }

  @Test
  void repeatedAndReorderedWindowsProduceIdenticalCoverage() {
    Map<String, Object> attack = row(10, "INTERACT_NPC", List.of());
    Map<String, Object> eat = row(11, "INTERACT_INVENTORY_ITEM", List.of("tiles"));
    var report = CombatRecordingCoverage.summarize("session", List.of(attack, eat, attack));
    assertThat(report)
        .isEqualTo(CombatRecordingCoverage.summarize("session", List.of(eat, attack)));
    assertThat(report.distinctTicks()).isEqualTo(2);
    assertThat(report.distinctRows()).isEqualTo(2);
    assertThat(report.actionCounts()).containsEntry("INTERACT_NPC", 1L);
    assertThat(report.deferredTickCounts()).containsExactly(Map.entry("tiles", 1L));
  }

  @Test
  void unknownLabelsAreReportedWithoutCountingRawInputAsActions() {
    var report =
        CombatRecordingCoverage.summarize(
            "session", List.of(row(10, "mouseX", List.of("mouse_x", "tiles"))));
    assertThat(report.invalidLabels()).isEqualTo(1);
    assertThat(report.actionCounts()).isEmpty();
    assertThat(report.deferredTickCounts()).containsEntry("tiles", 1L);
  }

  @Test
  void conflictingRowsCannotInflateEvidence() {
    assertThatThrownBy(
            () ->
                CombatRecordingCoverage.summarize(
                    "session",
                    List.of(row(10, "INTERACT_NPC", List.of()), row(10, "WALK_TO", List.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicting");
  }

  @Test
  void noRecordingsProvidesEmptyEvidenceRatherThanReadiness() {
    var report = CombatRecordingCoverage.summarize("session", List.of());
    assertThat(report.distinctTicks()).isZero();
    assertThat(report.actionCounts()).isEmpty();
    assertThat(report.deferredTickCounts()).isEmpty();
  }
}
