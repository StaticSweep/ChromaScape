package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.AlignedAction;
import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.RecordingContext;
import com.chromascape.web.aiplayer.prototype.FishingState.InventoryFeature;
import com.chromascape.web.aiplayer.prototype.FishingState.NearbyNpc;
import com.chromascape.web.aiplayer.prototype.FishingState.PlayerFeature;
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

/** Tests the replay-gated held-out evaluation. */
class FishingReplayServiceTest {

  private static final SemanticAction NET_FISHING_SPOT =
      SemanticAction.interactNpc("net", "fishing spot");

  @TempDir Path tempDir;

  private FishingPreflightService preflightService;
  private FishingRecordingLoader loader;
  private FishingDatasetBuilderService modelStore;
  private ObjectMapper objectMapper;
  private FishingReplayService replayService;
  private DebugSession testSession;

  @BeforeEach
  void setUp() {
    preflightService = mock(FishingPreflightService.class);
    loader = mock(FishingRecordingLoader.class);
    modelStore = mock(FishingDatasetBuilderService.class);
    objectMapper = new ObjectMapper().findAndRegisterModules();
    replayService = new FishingReplayService(preflightService, loader, modelStore, objectMapper);
    ReflectionTestUtils.setField(replayService, "outputDir", tempDir.toString());
    ReflectionTestUtils.setField(replayService, "aiPlayerEnabled", true);

    testSession = new DebugSession("debug_test", "acct", "script", LocalDateTime.now(), 0L);
    testSession.setRecordingId("recording_test");
    testSession.setEndTick(2000L);
    testSession.transitionTo(DebugSessionLifecycleState.FINALIZED);
    when(preflightService.resolveRecording("recording_test")).thenReturn(testSession);
  }

  private FishingState stateAt(int cp) {
    PlayerFeature player = new PlayerFeature(100, 100, 0, false, true, -1);
    InventoryFeature inventory = new InventoryFeature(cp + 1, cp + 1, cp);
    NearbyNpc spot = new NearbyNpc("fishing spot", 101, 100, 0, 1);
    return new FishingState(player, inventory, List.of(spot));
  }

  private FishingModel model(List<SemanticAction> actionsPerGate) {
    List<TrainingExample> examples = new ArrayList<>();
    for (int cp = 0; cp < actionsPerGate.size(); cp++) {
      examples.add(new TrainingExample(stateAt(cp), actionsPerGate.get(cp), 5000L + cp));
    }
    Map<String, Object> featureWeights = new LinkedHashMap<>();
    FishingDemonstrationPolicy.FEATURE_WEIGHTS.forEach(featureWeights::put);
    return new FishingModel(
        1,
        "fishing_m",
        FishingDemonstrationPolicy.KIND,
        "now",
        "fishing_dataset_train",
        "recording_train",
        "debug_train",
        5,
        List.of(NET_FISHING_SPOT),
        examples,
        featureWeights,
        FishingDemonstrationPolicy.MAX_ACCEPTED_POSITION_DISTANCE);
  }

  private TickSnapshot snapshot(long tick, int total, long xp, List<String> npcNames) {
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
            "debug_test", tick, LocalDateTime.now(), objectMapper.valueToTree(playerState));
    List<Map<String, Object>> inventory = new ArrayList<>();
    if (total > 0) {
      inventory.add(Map.of("slot", 0, "itemId", 1, "quantity", total));
    }
    snap.setInventory(objectMapper.valueToTree(inventory));
    List<Map<String, Object>> npcs = new ArrayList<>();
    for (String name : npcNames) {
      npcs.add(Map.of("name", name, "x", 101, "y", 100, "plane", 0));
    }
    snap.setNpcs(objectMapper.valueToTree(npcs));
    return snap;
  }

  /**
   * Builds a clean five-catch test recording. Actions land on ticks 1000..1004 with catch progress
   * 0..4 (inventory totals 1..5); a final snapshot at tick 1005 brings the total to 6.
   */
  private RecordingContext buildContext(boolean includeSpotAtAllGates, int finalTotal) {
    List<TickSnapshot> snapshots = new ArrayList<>();
    List<AlignedAction> aligned = new ArrayList<>();
    List<PlayerAction> actions = new ArrayList<>();
    Map<Long, Integer> totals = new LinkedHashMap<>();
    long xp = 100L;
    for (int i = 0; i < 5; i++) {
      long tick = 1000L + i;
      int total = 1 + i;
      List<String> npcNames =
          includeSpotAtAllGates || i != 1 ? List.of("Fishing spot") : List.of("Goblin");
      TickSnapshot snap = snapshot(tick, total, xp, npcNames);
      snapshots.add(snap);
      totals.put(tick, total);
      PlayerAction action =
          new PlayerAction(tick, 0, "Net", "Fishing spot", "NPC_FIRST_OPTION", 1, 1, 0, tick);
      actions.add(action);
      aligned.add(new AlignedAction(action, snap, tick, false));
      xp += 20L;
    }
    TickSnapshot last = snapshot(1005L, finalTotal, xp, List.of("Fishing spot"));
    snapshots.add(last);
    totals.put(1005L, finalTotal);

    return new RecordingContext(
        testSession,
        snapshots,
        actions,
        actions,
        List.of(),
        aligned,
        totals,
        1,
        finalTotal,
        finalTotal - 1,
        finalTotal - 1,
        100L,
        100L + 100L,
        true,
        true,
        true,
        true,
        false,
        0,
        0);
  }

  @Test
  @DisplayName("a complete held-out replay passes and persists its report")
  void completeReplayPasses() throws Exception {
    when(modelStore.loadModel("fishing_m"))
        .thenReturn(
            model(
                List.of(
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT)));
    when(preflightService.preflight("recording_test"))
        .thenReturn(
            PreflightReport.ready("recording_test", "debug_test", true, 6, 5, 5, 5, 0, 0, 5, true));
    when(loader.load(testSession)).thenReturn(buildContext(true, 6));

    RunReport report = replayService.run("fishing_m", "recording_test");

    assertThat(report.status()).isEqualTo("PASSED");
    assertThat(report.decisionTimingMode()).isEqualTo("RECORDED_DECISION_GATES");
    assertThat(report.expectedDecisionCount()).isEqualTo(5);
    assertThat(report.matchedDecisionCount()).isEqualTo(5);
    assertThat(report.mismatchCount()).isZero();
    assertThat(report.abstentionCount()).isZero();
    assertThat(report.observedCatchDelta()).isEqualTo(5);
    assertThat(report.fishingXpIncreased()).isTrue();

    assertThat(report.reportPath()).isNotBlank();
    Path persisted = Path.of(report.reportPath());
    assertThat(Files.exists(persisted)).isTrue();
    Map<String, Object> body = objectMapper.readValue(persisted.toFile(), Map.class);
    assertThat(body.get("status")).isEqualTo("PASSED");
    assertThat(body).containsKey("runId");
  }

  @Test
  @DisplayName("a wrong option fails at the correct decision tick")
  void wrongOptionFailsAtCorrectTick() {
    when(modelStore.loadModel("fishing_m"))
        .thenReturn(
            model(
                List.of(
                    NET_FISHING_SPOT,
                    SemanticAction.interactNpc("bait", "fishing spot"),
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT)));
    when(preflightService.preflight("recording_test"))
        .thenReturn(
            PreflightReport.ready("recording_test", "debug_test", true, 6, 5, 5, 5, 0, 0, 5, true));
    when(loader.load(testSession)).thenReturn(buildContext(true, 6));

    RunReport report = replayService.run("fishing_m", "recording_test");

    assertThat(report.status()).isEqualTo("FAILED");
    assertThat(report.failureCode()).isEqualTo("ACTION_MISMATCH");
    assertThat(report.failureTick()).isEqualTo(1001L);
    assertThat(report.expected().option()).isEqualTo("net");
    assertThat(report.predicted().option()).isEqualTo("bait");
    assertThat(report.matchedDecisionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a wrong target fails at the correct decision tick")
  void wrongTargetFailsAtCorrectTick() {
    when(modelStore.loadModel("fishing_m"))
        .thenReturn(
            model(
                List.of(
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    new SemanticAction("INTERACT_NPC", "net", "fish spot"),
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT)));
    when(preflightService.preflight("recording_test"))
        .thenReturn(
            PreflightReport.ready("recording_test", "debug_test", true, 6, 5, 5, 5, 0, 0, 5, true));
    when(loader.load(testSession)).thenReturn(buildContext(true, 6));

    RunReport report = replayService.run("fishing_m", "recording_test");

    assertThat(report.status()).isEqualTo("FAILED");
    assertThat(report.failureCode()).isEqualTo("ACTION_MISMATCH");
    assertThat(report.failureTick()).isEqualTo(1002L);
    assertThat(report.expected().target()).isEqualTo("fishing spot");
    assertThat(report.predicted().target()).isEqualTo("fish spot");
  }

  @Test
  @DisplayName("abstention stops the run at the offending tick")
  void abstentionStopsRun() {
    when(modelStore.loadModel("fishing_m"))
        .thenReturn(
            model(
                List.of(
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT)));
    when(preflightService.preflight("recording_test"))
        .thenReturn(
            PreflightReport.ready("recording_test", "debug_test", true, 6, 5, 5, 5, 0, 0, 5, true));
    when(loader.load(testSession)).thenReturn(buildContext(false, 6));

    RunReport report = replayService.run("fishing_m", "recording_test");

    assertThat(report.status()).isEqualTo("FAILED");
    assertThat(report.failureCode()).isEqualTo("ABSTENTION");
    assertThat(report.failureTick()).isEqualTo(1001L);
    assertThat(report.matchedDecisionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("fewer than five catches fails")
  void fewerThanFiveCatchesFails() {
    when(modelStore.loadModel("fishing_m"))
        .thenReturn(
            model(
                List.of(
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT)));
    PreflightReport.PreflightIssue issue =
        new PreflightReport.PreflightIssue(
            PreflightReport.IssueCode.CATCH_COUNT_MISMATCH,
            "Recording must show exactly 5 additions");
    when(preflightService.preflight("recording_test"))
        .thenReturn(
            new PreflightReport(
                "NOT_READY",
                "recording_test",
                "debug_test",
                true,
                6,
                5,
                5,
                5,
                0,
                0,
                3,
                true,
                List.of(issue)));

    RunReport report = replayService.run("fishing_m", "recording_test");

    assertThat(report.status()).isEqualTo("FAILED");
    assertThat(report.failureCode()).isEqualTo("CATCH_COUNT_MISMATCH");
    assertThat(report.observedCatchDelta()).isEqualTo(3);
    assertThat(report.issues()).hasSize(1);
  }

  @Test
  @DisplayName("using the training recording as the test recording is rejected")
  void sameTrainTestRecordingRejected() {
    when(modelStore.loadModel("fishing_m"))
        .thenReturn(
            model(
                List.of(
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT)));
    DebugSession trainingSession =
        new DebugSession("debug_train", "acct", "script", LocalDateTime.now(), 0L);
    trainingSession.setRecordingId("recording_train");
    trainingSession.setEndTick(2000L);
    when(preflightService.resolveRecording("recording_train")).thenReturn(trainingSession);

    assertThatThrownBy(() -> replayService.run("fishing_m", "recording_train"))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> {
              PrototypeApiException ex = (PrototypeApiException) e;
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(ex.getCode()).isEqualTo("SAME_TRAIN_TEST_RECORDING");
            });
  }

  @Test
  @DisplayName("a non-finalized test recording is rejected with a 409")
  void nonFinalizedRecordingRejected() {
    when(modelStore.loadModel("fishing_m"))
        .thenReturn(
            model(
                List.of(
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT,
                    NET_FISHING_SPOT)));
    DebugSession active =
        new DebugSession("debug_active", "acct", "script", LocalDateTime.now(), 0L);
    active.setRecordingId("recording_active");
    when(preflightService.resolveRecording("recording_active")).thenReturn(active);

    assertThatThrownBy(() -> replayService.run("fishing_m", "recording_active"))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> {
              PrototypeApiException ex = (PrototypeApiException) e;
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(ex.getCode()).isEqualTo("SESSION_NOT_FINALIZED");
            });
  }
}
