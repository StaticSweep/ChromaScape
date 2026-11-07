package com.chromascape.web.replay.worker;

import static org.assertj.core.api.Assertions.*;

import com.chromascape.web.replay.error.ReplayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ReplaySourceScheduleTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private ObjectNode manifest() {
    ObjectNode doc = mapper.createObjectNode().put("manifestVersion", 2);
    doc.putObject("recording").put("startTick", 40).put("endTick", 60);
    var actions = doc.putArray("actions");
    for (int[] pair : new int[][] {{42, 3}, {42, 8}, {45, 0}}) {
      actions
          .addObject()
          .put("type", "INTERACT_NPC")
          .put("targetStableId", "source:spot")
          .put("option", "net")
          .put("sourceActionTick", pair[0])
          .put("sourceActionSequence", pair[1])
          .put("provenance", "CAPTURED")
          .put("mappingVersion", "1.1.0");
    }
    return doc;
  }

  @Test
  void futureActionsUseOnlyBoundedActionlessTicksThenExactZeroTickSourceRequests() {
    var doc = manifest();
    var schedule = new ReplaySourceSchedule(doc);
    assertThat(schedule.timed()).isTrue();
    assertThat(schedule.next(0, 40, 1).toString())
        .isEqualTo("{\"expectedTick\":40,\"maxTicks\":1}");
    assertThat(schedule.next(0, 41, 10000).toString())
        .isEqualTo("{\"expectedTick\":41,\"maxTicks\":1}");
    var first = schedule.next(0, 42, 10000);
    assertThat(first.path("maxTicks").asInt()).isZero();
    assertThat(first.path("sourceActionIndex").asInt()).isZero();
    assertThat(first.path("action").toString())
        .isEqualTo(
            "{\"type\":\"INTERACT_NPC\",\"targetStableId\":\"source:spot\",\"option\":\"net\"}");
    assertThat(schedule.next(1, 42, 10000).path("maxTicks").asInt()).isZero();
    assertThat(schedule.next(1, 42, 10000).path("sourceActionIndex").asInt()).isEqualTo(1);
    assertThat(schedule.next(2, 42, 10000).path("maxTicks").asInt()).isEqualTo(3);
    assertThat(schedule.next(2, 45, 10000).path("sourceActionIndex").asInt()).isEqualTo(2);
    assertThat(doc.path("actions").get(0).has("sourceActionTick")).isTrue();
  }

  @Test
  void finalEffectsAdvanceToRecordingEndWithoutInventingSourceActions() {
    var schedule = new ReplaySourceSchedule(manifest());
    assertThat(schedule.tail(45, 10).toString()).isEqualTo("{\"expectedTick\":45,\"maxTicks\":10}");
    assertThat(schedule.tail(55, 10000).toString())
        .isEqualTo("{\"expectedTick\":55,\"maxTicks\":5}");
    assertThat(schedule.tail(60, 10000)).isNull();
    assertThatThrownBy(() -> schedule.tail(61, 10000)).isInstanceOf(ReplayException.class);
  }

  @Test
  void missingMalformedMixedDuplicateReorderedAndOutOfRangeTimingFailsStably() {
    List<Consumer<ObjectNode>> edits =
        List.of(
            d -> ((ObjectNode) d.path("actions").get(0)).remove("sourceActionSequence"),
            d -> ((ObjectNode) d.path("actions").get(0)).put("sourceActionTick", "42"),
            d -> ((ObjectNode) d.path("actions").get(1)).put("sourceActionSequence", 3),
            d -> ((ObjectNode) d.path("actions").get(1)).put("sourceActionSequence", 2),
            d -> ((ObjectNode) d.path("actions").get(2)).put("sourceActionTick", 41),
            d -> ((ObjectNode) d.path("actions").get(2)).put("sourceActionTick", 61),
            d ->
                ((ObjectNode) d.path("actions").get(2))
                    .remove(List.of("sourceActionTick", "sourceActionSequence")),
            d -> ((ObjectNode) d.path("recording")).put("startTick", "40"));
    for (var edit : edits) {
      var doc = manifest();
      edit.accept(doc);
      assertThatThrownBy(() -> new ReplaySourceSchedule(doc))
          .isInstanceOf(ReplayException.class)
          .hasMessageContaining("SOURCE_ACTION_SCHEDULE_INVALID");
    }
  }

  @Test
  void staleTicksInvalidIndexesAndBoundsRefuseRatherThanReorderOrWiden() {
    var schedule = new ReplaySourceSchedule(manifest());
    for (int index : new int[] {-1, 3})
      assertThatThrownBy(() -> schedule.next(index, 42, 1)).isInstanceOf(ReplayException.class);
    for (int bound : new int[] {-1, 0, 10001})
      assertThatThrownBy(() -> schedule.next(0, 42, bound)).isInstanceOf(ReplayException.class);
    assertThatThrownBy(() -> schedule.next(0, 43, 1)).isInstanceOf(ReplayException.class);
  }

  @Test
  void sourceSnapshotIsDetachedAndUntimedLegacyBehaviorRemainsAdvancing() {
    var doc = manifest();
    var schedule = new ReplaySourceSchedule(doc);
    ((ObjectNode) doc.path("actions").get(0)).put("sourceActionTick", 59);
    assertThat(schedule.next(0, 42, 1).has("action")).isTrue();
    for (var action : doc.path("actions"))
      ((ObjectNode) action).remove(List.of("sourceActionTick", "sourceActionSequence"));
    var legacy = new ReplaySourceSchedule(doc);
    assertThat(legacy.timed()).isFalse();
    assertThat(legacy.next(1, 99, 100).path("maxTicks").asInt()).isEqualTo(100);
    assertThat(legacy.tail(99, 100)).isNull();
  }
}
