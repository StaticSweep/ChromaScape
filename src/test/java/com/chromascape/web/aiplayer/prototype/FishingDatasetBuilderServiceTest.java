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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Tests dataset + model artifact generation and the no-partial-artifacts guarantee. */
class FishingDatasetBuilderServiceTest {

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
    ReflectionTestUtils.setField(builder, "aiPlayerEnabled", true);
    ReflectionTestUtils.setField(builder, "shadowMode", false);
    ReflectionTestUtils.setField(builder, "controlEnabled", false);

    session = new DebugSession("debug_1", "acct", "script", LocalDateTime.now(), 0L);
    session.setRecordingId("recording_1");
    session.setEndTick(2000L);
    session.transitionTo(DebugSessionLifecycleState.FINALIZED);
    when(preflightService.resolveRecording("recording_1")).thenReturn(session);
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
  @DisplayName("training produces dataset, manifest, and model artifacts")
  void trainProducesArtifacts() throws Exception {
    when(preflightService.preflight("recording_1"))
        .thenReturn(
            PreflightReport.ready("recording_1", "debug_1", true, 2, 2, 2, 2, 0, 0, 1, true));
    when(loader.load(session)).thenReturn(trainingContext());

    TrainingResult result = builder.train("recording_1", 5);

    assertThat(result.status()).isEqualTo("TRAINED");
    assertThat(result.exampleCount()).isEqualTo(2);
    assertThat(result.actionVocabulary())
        .containsExactly(SemanticAction.interactNpc("net", "fishing spot"));
    assertThat(result.trainingRecordingId()).isEqualTo("recording_1");

    Path datasetPath = Path.of(result.datasetPath());
    assertThat(Files.exists(datasetPath)).isTrue();
    List<String> lines = Files.readAllLines(datasetPath);
    assertThat(lines).hasSize(2);
    for (String line : lines) {
      Map<String, Object> example = objectMapper.readValue(line, Map.class);
      assertThat(example).containsEntry("schemaVersion", 1);
      assertThat(example).containsEntry("recordingId", "recording_1");
      assertThat(((Number) example.get("sourceTick")).longValue()).isIn(1000L, 1001L);
    }

    Path manifestPath = Path.of(result.manifestPath());
    assertThat(Files.exists(manifestPath)).isTrue();
    Map<String, Object> manifest = objectMapper.readValue(manifestPath.toFile(), Map.class);
    assertThat(manifest).containsEntry("acceptedActionCount", 2);
    assertThat(manifest).containsEntry("catchCount", 1);
    assertThat(manifest).containsKey("featureFlags");

    Path modelPath = Path.of(result.modelPath());
    assertThat(Files.exists(modelPath)).isTrue();
    Map<String, Object> model = objectMapper.readValue(modelPath.toFile(), Map.class);
    assertThat(model).containsEntry("kind", "FISHING_DEMONSTRATION_BASELINE");
    assertThat(model).containsEntry("maxAcceptedPositionDistance", 12);
    assertThat(((List<?>) model.get("examples"))).hasSize(2);
  }

  @Test
  @DisplayName("failed validation produces no partial artifacts")
  void noPartialArtifactsOnValidationFailure() {
    PreflightReport.PreflightIssue issue =
        new PreflightReport.PreflightIssue(
            PreflightReport.IssueCode.CATCH_COUNT_MISMATCH, "must be 5");
    when(preflightService.preflight("recording_1"))
        .thenReturn(
            new PreflightReport(
                "NOT_READY",
                "recording_1",
                "debug_1",
                true,
                2,
                2,
                2,
                2,
                0,
                0,
                3,
                true,
                List.of(issue)));

    assertThatThrownBy(() -> builder.train("recording_1", 5))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> {
              PrototypeApiException ex = (PrototypeApiException) e;
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(ex.getCode()).isEqualTo("CATCH_COUNT_MISMATCH");
            });

    Path datasetsDir = tempDir.resolve("fishing").resolve("datasets");
    assertThat(Files.exists(datasetsDir)).isFalse();
  }

  @Test
  @DisplayName("unsupported catch counts are rejected before validation")
  void unsupportedCatchCountRejected() {
    assertThatThrownBy(() -> builder.train("recording_1", 3))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e ->
                assertThat(((PrototypeApiException) e).getCode())
                    .isEqualTo("UNSUPPORTED_CATCH_COUNT"));
  }

  @Test
  @DisplayName("model IDs cannot escape the configured artifact directory")
  void modelIdPathTraversalRejected() {
    assertThatThrownBy(() -> builder.loadModel("../outside"))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> assertThat(((PrototypeApiException) e).getCode()).isEqualTo("INVALID_MODEL_ID"));
  }
}
