package com.chromascape.web.debug.tickdebugger.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Telemetry stays low-cardinality: codes only, bounded keys, no actor/source/URL labels. */
class MouseVideoTelemetryTest {

  @Test
  @SuppressWarnings("unchecked")
  void countsRefusalsByCodeWithABoundedKeySet() {
    MouseVideoTelemetry telemetry = new MouseVideoTelemetry();
    telemetry.refused("CONFLICT");
    telemetry.refused("CONFLICT");
    telemetry.refused(null);
    for (int index = 0; index < 100; index++) {
      telemetry.refused("CODE_" + index);
    }

    Map<String, Long> refusals = (Map<String, Long>) telemetry.snapshot().get("refusalsByCode");
    assertThat(refusals).containsEntry("CONFLICT", 2L).containsEntry("UNKNOWN", 1L);
    assertThat(refusals).hasSizeLessThanOrEqualTo(65);
    assertThat(refusals.get("OTHER")).isPositive();
  }

  @Test
  void countsFencedAndResolvedReconciliations() {
    MouseVideoTelemetry telemetry = new MouseVideoTelemetry();
    telemetry.reconciliationsRequired(0);
    telemetry.reconciliationsRequired(2);
    telemetry.reconciliationRequired();
    telemetry.reconciliationResolved();

    Map<String, Object> snapshot = telemetry.snapshot();
    assertThat(snapshot).containsEntry("reconciliationsRequired", 3L);
    assertThat(snapshot).containsEntry("reconciliationsResolved", 1L);
  }
}
