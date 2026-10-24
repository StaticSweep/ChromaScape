package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.AlignedAction;
import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.RecordingContext;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionLifecycleState;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Tests dataset + model artifact generation and the no-partial-artifacts guarantee. */
class FishingArtifactRegistryIntegrationTest {

  @TempDir Path tempDir;

  private FishingPreflightService preflightService;
  private FishingRecordingLoader loader;
  private ObjectMapper objectMapper;
  private FishingDatasetBuilderService builder;
  private DebugSession session;

  @BeforeEach
  void setUp() {
    preflightService = mock(FishingPreflightService.class);
    loader = mock(FishingRecordingLoader.class);
    objectMapper = new ObjectMapper().findAndRegisterModules();
    builder = new FishingDatasetBuilderService(preflightService, loader, objectMapper);
    ReflectionTestUtils.setField(builder, "outputDir", tempDir.toString());
    ReflectionTestUtils.setField(
        builder,
        "durabilityMode",
        com.chromascape.web.artifact.TestDurability.positiveMode().name());
    ReflectionTestUtils.setField(builder, "aiPlayerEnabled", true);
    ReflectionTestUtils.setField(builder, "shadowMode", false);
    ReflectionTestUtils.setField(builder, "controlEnabled", false);

    session = new DebugSession("debug_1", "acct", "script", LocalDateTime.now(), 0L);
    session.setRecordingId("recording_1");
    session.setEndTick(2000L);
    session.transitionTo(DebugSessionLifecycleState.FINALIZED);
    when(preflightService.resolveRecording("recording_1")).thenReturn(session);
    var contexts = mock(com.chromascape.web.recording.provenance.BehaviorRunContextService.class);
    var context =
        objectMapper.createObjectNode().put("runId", "frozen-run").put("profileVersion", 1);
    when(contexts.requireTrainingContext("recording_1")).thenReturn(context);
    ReflectionTestUtils.setField(builder, "runContexts", contexts);
    var frames = mock(com.chromascape.web.recording.provenance.CanonicalFrameStore.class);
    when(frames.atTick(
            org.mockito.ArgumentMatchers.eq("recording_1"), org.mockito.ArgumentMatchers.anyLong()))
        .thenAnswer(
            invocation -> {
              long tick = invocation.getArgument(1);
              var payload = objectMapper.createObjectNode();
              payload.put("tick", tick);
              var player = payload.putObject("playerState");
              player
                  .put("worldX", 100)
                  .put("worldY", 100)
                  .put("plane", 0)
                  .put("moving", false)
                  .put("idle", true)
                  .put("animationId", -1);
              var semantic = payload.putObject("semanticSnapshot");
              semantic
                  .putObject("inventory")
                  .put("occupiedSlots", 1)
                  .put("totalQuantity", 1)
                  .put("positiveDeltaFromStart", 0);
              semantic.putArray("nearbyNpcs");
              payload
                  .putArray("semanticEvents")
                  .addObject()
                  .put("sourceTick", tick)
                  .put("actionId", "INTERACT_NPC_NET")
                  .put("source", "CLIENT_EVENT")
                  .put("humanObserved", true);
              var frame = mock(com.chromascape.web.recording.provenance.CanonicalTickFrame.class);
              when(frame.payload()).thenReturn(payload);
              when(frame.digest()).thenReturn("sha256:" + "a".repeat(64));
              return frame;
            });
    ReflectionTestUtils.setField(builder, "canonicalFrames", frames);
  }

  private TickSnapshot snapshot(long tick, int total, long xp) {
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", 100);
    position.put("y", 100);
    position.put("z", 100);
    position.put("plane", 0);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("position", position);
    playerState.put("worldX", 100);
    playerState.put("worldY", 100);
    playerState.put("plane", 0);
    playerState.put("isMoving", false);
    playerState.put("isIdle", true);
    playerState.put("animationId", -1);
    Map<String, Object> skills = new LinkedHashMap<>();
    Map<String, Object> fishing = new LinkedHashMap<>();
    fishing.put("xp", xp);
    skills.put("FISHING", fishing);
    playerState.put("skills", skills);

    TickSnapshot snap =
        new TickSnapshot(
            "debug_1", tick, LocalDateTime.now(), objectMapper.valueToTree(playerState));
    List<Map<String, Object>> inventory = new ArrayList<>();
    if (total > 0) {
      inventory.add(Map.of("slot", 0, "itemId", 1, "quantity", total));
    }
    snap.setInventory(objectMapper.valueToTree(inventory));
    List<Map<String, Object>> npcs =
        List.of(Map.of("name", "Fishing spot", "x", 101, "y", 100, "plane", 0));
    snap.setNpcs(objectMapper.valueToTree(npcs));
    return snap;
  }

  private RecordingContext trainingContext() {
    List<TickSnapshot> snapshots = new ArrayList<>();
    List<AlignedAction> aligned = new ArrayList<>();
    List<PlayerAction> actions = new ArrayList<>();
    Map<Long, Integer> totals = new LinkedHashMap<>();
    for (int i = 0; i < 2; i++) {
      long tick = 1000L + i;
      int total = 1 + i;
      TickSnapshot snap = snapshot(tick, total, 100L + i * 20L);
      snapshots.add(snap);
      totals.put(tick, total);
      PlayerAction action =
          new PlayerAction(tick, 0, "Net", "Fishing spot", "NPC_FIRST_OPTION", 1, 1, 0, tick);
      actions.add(action);
      aligned.add(new AlignedAction(action, snap, tick, false));
    }
    return new RecordingContext(
        session, snapshots, actions, actions, List.of(), aligned, totals, 1, 2, 1, 1, 0, 0, true,
        true, true, true, false, 0, 0);
  }

  @Test
  void fishingTrainPublishesRegisteredLineageAndCorruptionBlocksModelReload() throws Exception {
    when(preflightService.preflight("recording_1"))
        .thenReturn(
            PreflightReport.ready("recording_1", "debug_1", true, 2, 2, 2, 2, 0, 0, 1, true));
    when(loader.load(session)).thenReturn(trainingContext());
    var result = builder.train("recording_1", 5);
    assertThat(result.status()).isEqualTo("TRAINED");
    var registry =
        new com.chromascape.web.artifact.ImmutableArtifactStore(
            tempDir.resolve("artifacts"),
            104857600,
            1073741824,
            objectMapper,
            com.chromascape.web.artifact.TestDurability.positiveMode());
    var modelRef = registry.get("fishing-prototype", result.modelId());
    assertThat(modelRef.lineage()).containsEntry("recordingId", "recording_1");
    var datasetRef = registry.get("fishing-prototype", modelRef.dependencies().get("dataset"));
    var source = registry.get("fishing-prototype", datasetRef.dependencies().get("recording"));
    assertThat(source.kind())
        .isEqualTo(com.chromascape.web.artifact.ImmutableArtifactStore.Kind.RECORDING);
    assertThat(source.lineage().get("runContext")).contains("frozen-run");
    assertThat(registry.list("fishing-prototype")).hasSize(4);
    assertThat(builder.loadModel(result.modelId()).trainingRecordingId()).isEqualTo("recording_1");
    Files.write(registry.contentPath(datasetRef), new byte[] {1});
    assertThatThrownBy(() -> builder.loadModel(result.modelId()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
}
