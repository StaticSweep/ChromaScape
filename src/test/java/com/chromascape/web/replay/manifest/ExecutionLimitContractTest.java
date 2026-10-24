package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ExecutionLimitContractTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void defaultsUseElapsedTicksNotAbsoluteClockAndClampToNativeCeiling() {
    assertThat(ExecutionLimitContract.defaults(json, 50, 50).path("maximumTicks").asInt()).isZero();
    assertThat(ExecutionLimitContract.defaults(json, 50, 75).path("maximumTicks").asInt())
        .isEqualTo(25);
    assertThat(ExecutionLimitContract.defaults(json, 50, 10_000_000).path("maximumTicks").asInt())
        .isEqualTo(100_000);
    for (long[] range :
        new long[][] {{-1, 2}, {2, 1}, {0, 10_000_001}, {Long.MIN_VALUE, Long.MAX_VALUE}}) {
      assertThrows(
          ReplayException.class, () -> ExecutionLimitContract.defaults(json, range[0], range[1]));
    }
  }

  @Test
  void crossFieldValidationRejectsInvalidSourceRangesAndTickSpanEvenWithoutSchemaGate() {
    for (long[] range : new long[][] {{-1, 2}, {2, 1}, {0, 10_000_001}, {20, 21}}) {
      var manifest = json.createObjectNode();
      manifest.putObject("recording").put("startTick", range[0]).put("endTick", range[1]);
      manifest.set("execution", ExecutionLimitContract.defaults(json, 0, 2));
      assertThrows(ReplayException.class, () -> ExecutionLimitContract.validate(manifest));
    }
  }
}
