package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class NativeSourceCursorTest {
  private static ObjectNode state(int tick, int actions, int steps) {
    return JsonNodeFactory.instance.objectNode().put("schema", NativeRunLedger.SCHEMA)
        .put("tick", tick).put("acceptedActions", actions).put("acceptedSteps", steps);
  }

  @Test
  void actionlessAdvancesAndSameTickReceiptsHaveDistinctCounters() {
    var cursor = NativeSourceCursor.initial(state(0, 0, 0), false);
    cursor = NativeSourceCursor.advance(cursor, state(5, 0, 1));
    assertThat(NativeSourceCursor.acceptedActions(cursor)).isZero();
    cursor = NativeSourceCursor.advance(cursor, state(5, 1, 2));
    cursor = NativeSourceCursor.advance(cursor, state(5, 2, 3));
    cursor = NativeSourceCursor.advance(cursor, state(10, 2, 4));
    assertThat(NativeSourceCursor.acceptedActions(cursor)).isEqualTo(2);
    assertThat(cursor.path("acceptedSteps").asInt()).isEqualTo(4);
  }

  @Test
  void verifiedRestoreRetainsInheritedActionsWithoutInventingSuffixActions() {
    var cursor = NativeSourceCursor.initial(state(20, 2, 5), true);
    assertThat(NativeSourceCursor.acceptedActions(cursor)).isEqualTo(2);
    assertThat(cursor.path("inheritedSourceActions").asInt()).isEqualTo(2);
    var next = NativeSourceCursor.advance(cursor, state(25, 2, 6));
    assertThat(next.path("inheritedSourceActions").asInt()).isEqualTo(2);
    assertThat(NativeSourceCursor.acceptedActions(next)).isEqualTo(2);
  }

  @Test
  void rejectsSkippedOrRewoundProgressWithoutChangingStoredCursor() {
    var cursor = NativeSourceCursor.initial(state(5, 1, 2), true);
    var saved = cursor.deepCopy();
    for (var bad : new ObjectNode[]{state(4, 1, 3), state(5, 0, 3),
        state(5, 3, 3), state(5, 1, 4), state(5, 1, 2)}) {
      assertThatThrownBy(() -> NativeSourceCursor.advance(cursor, bad))
          .hasMessageContaining("NATIVE_SOURCE_CURSOR_INVALID");
      assertThat(cursor).isEqualTo(saved);
    }
  }

  @Test
  void refusesMalformedOrUnverifiedResetEvidence() {
    assertThatThrownBy(() -> NativeSourceCursor.initial(state(5, 1, 2), false))
        .hasMessageContaining("NATIVE_SOURCE_CURSOR_INVALID");
    var valid = NativeSourceCursor.initial(state(0, 0, 0), false);
    for (var bad : new ObjectNode[]{valid.deepCopy().put("extra", true),
        valid.deepCopy().put("acceptedSourceActions", 0.5),
        valid.deepCopy().put("acceptedSourceActions", -1),
        valid.deepCopy().put("inheritedSourceActions", 1),
        valid.deepCopy().put("acceptedSteps", 100_001)}) {
      assertThatThrownBy(() -> NativeSourceCursor.acceptedActions(bad))
          .hasMessageContaining("NATIVE_SOURCE_CURSOR_INVALID");
    }
  }
}
