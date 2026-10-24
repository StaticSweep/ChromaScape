package com.chromascape.web.replay.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.replay.conversion.ActionProvenance;
import com.chromascape.web.replay.conversion.ConversionMode;
import com.chromascape.web.replay.conversion.ConversionQuality;
import com.chromascape.web.replay.conversion.ConvertedReplayAction;
import com.chromascape.web.replay.conversion.ReplayActionConversionResult;
import com.chromascape.web.replay.error.ReplayErrorCode;
import com.chromascape.web.replay.error.ReplayException;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class NativeNpcBindingContractTest {
  private final ObjectMapper json = new ObjectMapper();
  private final ScenarioCompilationEvidence evidence =
      new ScenarioCompilationEvidence(json, new CanonicalProjectionService());

  private ObjectNode fixture() {
    ObjectNode document = json.createObjectNode().put("manifestVersion", 2);
    document.putObject("recording").put("startTick", 0).put("endTick", 20);
    ObjectNode state = document.putObject("initialState");
    state.putArray("npcs");
    state
        .putObject("nativeFishingWorld")
        .put("schema", "xrsps-native-fishing-world/v1")
        .put("catalogChecksum", "sha256:" + "a".repeat(64))
        .putArray("spawnIndices")
        .add(7);
    ArrayNode rows =
        state
            .putObject("nativeNpcBindings")
            .put("schema", "xrsps-native-npc-bindings/v1")
            .put("catalogChecksum", "sha256:" + "a".repeat(64))
            .putArray("bindings");
    ArrayNode actions = document.putArray("actions");
    ArrayNode targets =
        document.putObject("compilationEvidence").putObject("world").putArray("actionTargets");
    for (int i = 0; i < 2; i++) {
      ObjectNode row =
          rows.addObject()
              .put("actionIndex", i)
              .put("tick", 0)
              .put("sequence", i)
              .put("sourceStableId", "captured:spot")
              .put("spawnIndex", 7)
              .put("npcTypeId", 1530)
              .put("sourceSnapshotRef", "snapshot:sha256:" + "b".repeat(64));
      row.putObject("position").put("x", 3081).put("y", 3227).put("plane", 0);
      actions
          .addObject()
          .put("type", "INTERACT_NPC")
          .put("targetStableId", "captured:spot")
          .put("option", "net")
          .put("sourceActionTick", 0)
          .put("sourceActionSequence", i);
      targets
          .addObject()
          .put("kind", "NPC")
          .put("tick", 0)
          .put("sequence", i)
          .put("stableId", "captured:spot")
          .put("sourceRef", "snapshot:sha256:" + "b".repeat(64) + "/action:" + i);
    }
    return document;
  }

  private ObjectNode state(ObjectNode document) {
    return (ObjectNode) document.path("initialState");
  }

  private ObjectNode world(ObjectNode document) {
    return (ObjectNode) state(document).path("nativeFishingWorld");
  }

  private ObjectNode bindings(ObjectNode document) {
    return (ObjectNode) state(document).path("nativeNpcBindings");
  }

  private ObjectNode row(ObjectNode document, int index) {
    return (ObjectNode) bindings(document).path("bindings").get(index);
  }

  private ObjectNode action(ObjectNode document) {
    return (ObjectNode) document.path("actions").get(0);
  }

  private ObjectNode target(ObjectNode document) {
    return (ObjectNode)
        document.path("compilationEvidence").path("world").path("actionTargets").get(0);
  }

  @Test
  void acceptsClosedOrderedBindingWithoutMutatingTheDeclaration() {
    ObjectNode document = fixture(), before = document.deepCopy();
    NativeNpcBindingContract.validateManifest(document);
    assertThat(document).isEqualTo(before);
  }

  @Test
  void rejectsUnknownUnboundedUnselectedDuplicateReorderedAndReservedDeclarations() {
    List<Consumer<ObjectNode>> changes =
        List.of(
            d -> world(d).put("schema", "v2"),
            d -> world(d).put("endpoint", "https://example.invalid"),
            d -> world(d).putArray("spawnIndices").add(7).add(7),
            d -> world(d).putArray("spawnIndices").add(7).add(6),
            d -> world(d).putArray("spawnIndices").add(100000),
            d -> {
              ArrayNode values = world(d).putArray("spawnIndices");
              for (int i = 0; i < 65; i++) values.add(i);
            },
            d -> bindings(d).put("catalogChecksum", "sha256:" + "c".repeat(64)),
            d -> bindings(d).put("aliases", "unchecked"),
            d -> bindings(d).putArray("bindings"),
            d -> row(d, 1).put("actionIndex", 0),
            d -> row(d, 1).put("sequence", 0),
            d -> row(d, 0).put("tick", 10000001),
            d -> row(d, 0).put("sequence", 1000001),
            d -> row(d, 0).put("spawnIndex", 8),
            d -> row(d, 0).put("npcTypeId", 65535),
            d -> row(d, 0).put("sourceStableId", "native-fishing-spawn:7"),
            d -> row(d, 0).put("sourceStableId", "\u00a0captured:spot"),
            d -> row(d, 0).put("sourceStableId", "captured:spot\ufeff"),
            d -> row(d, 0).put("sourceStableId", "captured:\u0000spot"),
            d -> row(d, 0).put("sourceStableId", "x".repeat(129)),
            d -> ((ObjectNode) row(d, 0).path("position")).put("x", 16384),
            d -> ((ObjectNode) row(d, 0).path("position")).put("plane", 4),
            d -> ((ObjectNode) row(d, 0).path("position")).put("script", "no"),
            d -> row(d, 0).put("sourceSnapshotRef", "snapshot:not-a-pin"),
            d -> state(d).putArray("npcs").addObject().put("stableId", "injected"),
            d -> state(d).putObject("exchange"),
            d -> state(d).put("questProfile", "rune_mysteries"));
    for (var change : changes) {
      ObjectNode document = fixture();
      change.accept(document);
      ReplayException error =
          assertThrows(
              ReplayException.class, () -> NativeNpcBindingContract.validateManifest(document));
      assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.VALIDATION_ERROR);
      assertThat(error.getMessage()).startsWith("NATIVE_NPC_BINDING_INVALID:");
    }
  }

  @Test
  void exactSourceActionAndUnambiguousNumericAuditCannotBeForged() {
    List<Consumer<ObjectNode>> changes =
        List.of(
            d -> row(d, 0).put("actionIndex", 9999),
            d -> action(d).put("type", "INTERACT_OBJECT"),
            d -> action(d).put("sourceActionTick", 1),
            d -> action(d).put("sourceActionSequence", 1),
            d -> action(d).put("targetStableId", "lookalike"),
            d -> target(d).put("tick", "0"),
            d -> target(d).put("sequence", "0"),
            d -> target(d).put("kind", "OBJECT"),
            d -> target(d).put("stableId", "lookalike"),
            d -> target(d).put("sourceRef", "snapshot:sha256:" + "b".repeat(64) + "/action:99"),
            d ->
                ((ArrayNode) d.path("compilationEvidence").path("world").path("actionTargets"))
                    .add(target(d).deepCopy()),
            d -> ((ObjectNode) d.path("recording")).put("startTick", 1),
            d -> state(d).remove("nativeFishingWorld"));
    for (var change : changes) {
      ObjectNode document = fixture();
      change.accept(document);
      assertThrows(
          ReplayException.class, () -> NativeNpcBindingContract.validateManifest(document));
    }
  }

  private TickSnapshot source() {
    TickSnapshot snapshot = new TickSnapshot();
    snapshot.setSessionId("source-session");
    snapshot.setTick(0L);
    ObjectNode npc =
        json.createObjectNode()
            .put("stableId", "captured:spot")
            .put("npcId", 42)
            .put("npcTypeId", 1530)
            .put("size", 1)
            .put("spotId", "sea_small_net");
    npc.putObject("position").put("x", 3081).put("y", 3227).put("plane", 0);
    snapshot.setNpcs(json.createArrayNode().add(npc));
    return snapshot;
  }

  private ReplayActionConversionResult conversion() {
    var actions = new java.util.ArrayList<ConvertedReplayAction>();
    for (int i = 0; i < 2; i++) {
      RecordingAction action = new RecordingAction();
      action.setActionType("INTERACT_NPC");
      action.setTick(0);
      action.setSequence(i);
      action.setTarget(Map.of("stableId", "captured:spot"));
      action.setArguments(Map.of("option", "net"));
      actions.add(
          new ConvertedReplayAction(
              action, ActionProvenance.CAPTURED, "authored-test", null, null));
    }
    return new ReplayActionConversionResult(
        "source-session",
        0,
        20,
        ConversionMode.AUTO,
        ConversionQuality.BEST_EFFORT,
        actions,
        List.of(),
        List.of(),
        2,
        0,
        100.0,
        "sha256:authored");
  }

  @Test
  void nativeBindingsNeverWaiveOriginalNpcIdTypeSizeOrSpotPinsEvenForAuthoredBestEffortInput() {
    for (String field : List.of("npcId", "npcTypeId", "size", "spotId")) {
      TickSnapshot snapshot = source();
      ((ObjectNode) snapshot.getNpcs().get(0)).remove(field);
      ObjectNode document = fixture();
      for (int i = 0; i < 2; i++)
        row(document, i).put("sourceSnapshotRef", evidence.snapshotRef(snapshot));
      ReplayException error =
          assertThrows(
              ReplayException.class,
              () ->
                  NativeNpcBindingContract.validateSourceSnapshots(
                      state(document), List.of(snapshot), conversion(), evidence));
      assertThat(error.getErrorCode()).isEqualTo(ReplayErrorCode.SIM_INITIAL_WORLD_INCOMPLETE);
      assertThat(error.getMessage()).contains("authoritative " + field);
    }
  }

  @Test
  void sourceBindingsRetainOriginalSnapshotTypePositionAndIdentityInsteadOfTrustingOperatorState() {
    ObjectNode document = fixture();
    TickSnapshot snapshot = source();
    for (int i = 0; i < 2; i++)
      row(document, i).put("sourceSnapshotRef", evidence.snapshotRef(snapshot));
    ObjectNode before = document.deepCopy();
    NativeNpcBindingContract.validateSourceSnapshots(
        state(document), List.of(snapshot), conversion(), evidence);
    assertThat(document).isEqualTo(before);
    List<Consumer<ObjectNode>> changes =
        List.of(
            d -> row(d, 0).put("npcTypeId", 1544),
            d -> ((ObjectNode) row(d, 0).path("position")).put("x", 3082),
            d -> ((ObjectNode) row(d, 0).path("position")).put("plane", 1),
            d -> row(d, 0).put("sourceStableId", "lookalike"),
            d -> row(d, 0).put("sourceSnapshotRef", "snapshot:sha256:" + "f".repeat(64)),
            d -> row(d, 0).put("actionIndex", 1));
    for (var change : changes) {
      ObjectNode candidate = document.deepCopy();
      change.accept(candidate);
      assertThrows(
          ReplayException.class,
          () ->
              NativeNpcBindingContract.validateSourceSnapshots(
                  state(candidate), List.of(snapshot), conversion(), evidence));
    }
    assertThrows(
        ReplayException.class,
        () ->
            NativeNpcBindingContract.validateSourceSnapshots(
                state(document), List.of(snapshot, snapshot), conversion(), evidence));
  }
}
