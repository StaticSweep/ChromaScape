package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.prototype.FishingState.InventoryFeature;
import com.chromascape.web.aiplayer.prototype.FishingState.NearbyNpc;
import com.chromascape.web.aiplayer.prototype.FishingState.PlayerFeature;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Tests the live shadow decision service: gates, baselines, persistence, and status. */
class FishingShadowDecisionServiceTest {

  private static final SemanticAction NET_FISHING_SPOT =
      SemanticAction.interactNpc("net", "fishing spot");

  @TempDir Path tempDir;

  private ObjectMapper objectMapper;
  private FishingActiveModelService activeModelService;
  private FishingShadowDecisionWriter writer;
  private FishingShadowDecisionService service;
  private FishingModel model;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    activeModelService = mock(FishingActiveModelService.class);
    writer = new FishingShadowDecisionWriter(objectMapper);
    ReflectionTestUtils.setField(writer, "outputDir", tempDir.toString());
    writer.start();
    service = new FishingShadowDecisionService(activeModelService, objectMapper, writer);
    ReflectionTestUtils.setField(service, "outputDir", tempDir.toString());
    setFlags(true, true, false);
    model = modelWithCatchProgressStates();
    when(activeModelService.getActiveModel()).thenReturn(model);
    when(activeModelService.getActiveModelId()).thenReturn("fishing_ab12cd34");
    when(activeModelService.getActiveSnapshot())
        .thenReturn(
            new FishingActiveModelService.ActiveModelInfo(
                model, "fishing_ab12cd34", null, null, null));
  }

  @AfterEach
  void tearDown() {
    writer.stop();
  }

  private void setFlags(boolean enabled, boolean shadowMode, boolean controlEnabled) {
    setFlags(service, enabled, shadowMode, controlEnabled);
  }

  private void setFlags(
      FishingShadowDecisionService target,
      boolean enabled,
      boolean shadowMode,
      boolean controlEnabled) {
    ReflectionTestUtils.setField(target, "enabled", enabled);
    ReflectionTestUtils.setField(target, "shadowMode", shadowMode);
    ReflectionTestUtils.setField(target, "controlEnabled", controlEnabled);
  }

  /** Builds a service wired to a specific writer so persistence can be paused or observed. */
  private FishingShadowDecisionService serviceWithWriter(FishingShadowDecisionWriter targetWriter) {
    FishingShadowDecisionService target =
        new FishingShadowDecisionService(activeModelService, objectMapper, targetWriter);
    ReflectionTestUtils.setField(target, "outputDir", tempDir.toString());
    setFlags(target, true, true, false);
    return target;
  }

  private FishingModel modelWithCatchProgressStates() {
    return modelWithAction(NET_FISHING_SPOT);
  }

  private FishingModel modelWithAction(SemanticAction action) {
    List<TrainingExample> examples = new ArrayList<>();
    for (int cp = 0; cp < 5; cp++) {
      examples.add(new TrainingExample(stateAt(cp), action, 8200L + cp));
    }
    Map<String, Object> featureWeights = new LinkedHashMap<>();
    FishingDemonstrationPolicy.FEATURE_WEIGHTS.forEach(featureWeights::put);
    return new FishingModel(
        1,
        "fishing_ab12cd34",
        FishingDemonstrationPolicy.KIND,
        "2026-08-11T05:20:00Z",
        "fishing_dataset_1",
        "recording_train",
        "debug_train",
        5,
        List.of(action),
        examples,
        featureWeights,
        FishingDemonstrationPolicy.MAX_ACCEPTED_POSITION_DISTANCE);
  }

  private FishingState stateAt(int cp) {
    PlayerFeature player = new PlayerFeature(100, 100, 0, false, true, -1);
    InventoryFeature inventory = new InventoryFeature(cp + 1, cp + 1, cp);
    NearbyNpc spot = new NearbyNpc("fishing spot", 101, 100, 0, 1);
    return new FishingState(player, inventory, List.of(spot));
  }

  private TickBatchMessage batch(
      String sessionId,
      long tick,
      int x,
      int y,
      int plane,
      boolean moving,
      boolean idle,
      int animationId,
      int inventoryTotal,
      List<Map<String, Object>> npcs) {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setType("TICK_BATCH");
    batch.setSessionId(sessionId);
    batch.setTick(tick);
    batch.setTimestamp("2026-08-11T05:30:00Z");

    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", x);
    position.put("y", y);
    position.put("z", y);
    position.put("plane", plane);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("position", position);
    playerState.put("worldX", x);
    playerState.put("worldY", y);
    playerState.put("plane", plane);
    playerState.put("isMoving", moving);
    playerState.put("isIdle", idle);
    playerState.put("animationId", animationId);
    batch.setPlayerState(playerState);

    List<Map<String, Object>> inventory = new ArrayList<>();
    if (inventoryTotal > 0) {
      inventory.add(Map.of("slot", 0, "itemId", 303, "quantity", inventoryTotal));
    }
    batch.setInventory(inventory);
    batch.setNpcs(npcs);
    return batch;
  }

  private List<Map<String, Object>> fishingSpot(int x, int y, int plane) {
    return List.of(
        Map.of("name", "Fishing spot", "x", x, "y", y, "plane", plane),
        Map.of("name", "Goblin", "x", 300, "y", 300, "plane", plane));
  }

  @Test
  @DisplayName("all three feature flags enforce the shadow-only gate")
  void shadowOnlyGate() {
    setFlags(false, true, false);
    assertThat(
            service.evaluate(
                batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0))))
        .isNull();
    assertThat(service.status(null)).containsEntry("operational", false);

    setFlags(true, false, false);
    assertThat(service.status(null)).containsEntry("operational", false);

    setFlags(true, true, false);
    assertThat(service.status(null)).containsEntry("operational", true);
  }

  @Test
  @DisplayName("control enabled prevents inference")
  void controlEnabledPreventsInference() {
    setFlags(true, true, true);

    FishingShadowDecision decision =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(decision).isNull();
    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("operational", false);
    assertThat(status).containsEntry("inactiveReason", "INACTIVE_CONTROL_ENABLED");
  }

  @Test
  @DisplayName("idle state with a visible matching spot produces INTERACT_NPC")
  void idleStateProducesInteractNpc() {
    FishingShadowDecision decision =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));

    assertThat(decision).isNotNull();
    assertThat(decision.status()).isEqualTo("PREDICTED");
    assertThat(decision.action()).isNotNull();
    assertThat(decision.action().type()).isEqualTo("INTERACT_NPC");
    assertThat(decision.action().option()).isEqualTo("net");
    assertThat(decision.targetNpc()).isNotNull();
    assertThat(decision.targetNpc().distance()).isEqualTo(1);
    assertThat(decision.abstentionReason()).isNull();
    assertThat(decision.policyDistance()).isNotNull();
    assertThat(decision.matchedExampleTick()).isEqualTo(8200L);
    assertThat(decision.latencyMicros()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("movement produces ABSTAIN_MOVING")
  void movementAbstains() {
    FishingShadowDecision decision =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, true, false, -1, 1, fishingSpot(101, 100, 0)));

    assertThat(decision).isNotNull();
    assertThat(decision.status()).isEqualTo("ABSTAINED");
    assertThat(decision.abstentionReason()).isEqualTo("ABSTAIN_MOVING");
    assertThat(decision.action()).isNull();
  }

  @Test
  @DisplayName("animation or non-idle state produces ABSTAIN_BUSY")
  void busyStateAbstains() {
    FishingShadowDecision animated =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, 715, 1, fishingSpot(101, 100, 0)));
    assertThat(animated.abstentionReason()).isEqualTo("ABSTAIN_BUSY");

    FishingShadowDecision nonIdle =
        service.evaluate(
            batch("debug_1", 2, 100, 100, 0, false, false, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(nonIdle.abstentionReason()).isEqualTo("ABSTAIN_BUSY");
  }

  @Test
  @DisplayName("missing spot, plane mismatch, and excessive distance preserve their reasons")
  void policyGatesPreserveReasons() {
    FishingShadowDecision missingSpot =
        service.evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, List.of()));
    assertThat(missingSpot.abstentionReason()).isEqualTo("ABSTAIN_TARGET_MISSING");

    FishingShadowDecision planeMismatch =
        service.evaluate(
            batch("debug_1", 2, 100, 100, 1, false, true, -1, 1, fishingSpot(101, 100, 1)));
    assertThat(planeMismatch.abstentionReason()).isEqualTo("ABSTAIN_PLANE_MISMATCH");

    FishingShadowDecision tooFar =
        service.evaluate(
            batch("debug_1", 3, 120, 100, 0, false, true, -1, 1, fishingSpot(121, 100, 0)));
    assertThat(tooFar.abstentionReason()).isEqualTo("ABSTAIN_TOO_FAR");
  }

  @Test
  @DisplayName("missing active model produces ABSTAIN_NO_ACTIVE_MODEL")
  void noActiveModelAbstains() {
    when(activeModelService.getActiveModel()).thenReturn(null);

    FishingShadowDecision decision =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(decision).isNotNull();
    assertThat(decision.abstentionReason()).isEqualTo("ABSTAIN_NO_ACTIVE_MODEL");
  }

  @Test
  @DisplayName("missing required state produces ABSTAIN_INVALID_STATE")
  void missingRequiredStateAbstains() {
    TickBatchMessage batch =
        batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0));
    batch.setPlayerState(null);

    FishingShadowDecision decision = service.evaluate(batch);
    assertThat(decision).isNotNull();
    assertThat(decision.abstentionReason()).isEqualTo("ABSTAIN_INVALID_STATE");
  }

  @Test
  @DisplayName("duplicate and out-of-order ticks do not corrupt latest state or history")
  void duplicateAndOutOfOrderTicksIgnored() throws Exception {
    service.evaluate(
        batch("debug_1", 100, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(
            service.evaluate(
                batch("debug_1", 100, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0))))
        .isNull();
    assertThat(
            service.evaluate(
                batch("debug_1", 99, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0))))
        .isNull();
    service.evaluate(
        batch("debug_1", 101, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("latestTick", 101L);
    assertThat(status).containsEntry("processed", 2);
    assertThat(status).containsEntry("predicted", 2);

    List<FishingShadowDecision> history = service.history("debug_1", 50);
    assertThat(history).hasSize(2);
    assertThat(history.get(0).tick()).isEqualTo(101);
    assertThat(history.get(1).tick()).isEqualTo(100);
  }

  @Test
  @DisplayName("a duplicate first tick cannot change the inventory baseline")
  void duplicateFirstTickCannotChangeBaseline() {
    service.evaluate(
        batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(
            service.evaluate(
                batch("debug_1", 1, 100, 100, 0, false, true, -1, 50, fishingSpot(101, 100, 0))))
        .isNull();

    // With the original baseline of 1 and a total of 50 the delta is 49, so the policy must match
    // the catch-progress-4 demonstration (tick 8204). A baseline wrongly moved to 50 would match
    // the catch-progress-0 demonstration (tick 8200).
    FishingShadowDecision decision =
        service.evaluate(
            batch("debug_1", 2, 100, 100, 0, false, true, -1, 50, fishingSpot(101, 100, 0)));
    assertThat(decision).isNotNull();
    assertThat(decision.matchedExampleTick()).isEqualTo(8204L);

    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("processed", 2);
  }

  @Test
  @DisplayName("an out-of-order tick cannot change counters or the latest decision")
  void outOfOrderTickCannotChangeCountersOrLatest() throws Exception {
    FishingShadowDecision first =
        service.evaluate(
            batch("debug_1", 100, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(
            service.evaluate(
                batch("debug_1", 99, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0))))
        .isNull();
    writer.flush();

    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("latestTick", 100L);
    assertThat(status).containsEntry("processed", 1);
    assertThat(status).containsEntry("predicted", 1);
    assertThat(status).containsEntry("latestDecision", first);

    List<FishingShadowDecision> history = service.history("debug_1", 50);
    assertThat(history).hasSize(1);
    assertThat(history.get(0).tick()).isEqualTo(100);
  }

  @Test
  @DisplayName("an incomplete nested position falls back to valid flat coordinates")
  void incompleteNestedPositionFallsBackToFlat() {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setType("TICK_BATCH");
    batch.setSessionId("debug_1");
    batch.setTick(1);
    batch.setTimestamp("2026-08-11T05:30:00Z");
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("x", 100);
    Map<String, Object> playerState = new LinkedHashMap<>();
    playerState.put("position", position);
    playerState.put("worldX", 100);
    playerState.put("worldY", 100);
    playerState.put("plane", 0);
    playerState.put("isMoving", false);
    playerState.put("isIdle", true);
    playerState.put("animationId", -1);
    batch.setPlayerState(playerState);
    batch.setInventory(List.of(Map.of("quantity", 1)));
    batch.setNpcs(fishingSpot(101, 100, 0));

    FishingShadowDecision decision = service.evaluate(batch);
    assertThat(decision).isNotNull();
    assertThat(decision.status()).isEqualTo("PREDICTED");
  }

  @Test
  @DisplayName("an unsupported fishing option abstains")
  void unsupportedOptionAbstains() {
    when(activeModelService.getActiveModel())
        .thenReturn(modelWithAction(SemanticAction.interactNpc("bomb", "fishing spot")));

    FishingShadowDecision decision =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(decision).isNotNull();
    assertThat(decision.status()).isEqualTo("ABSTAINED");
    assertThat(decision.abstentionReason()).isEqualTo("ABSTAIN_UNSUPPORTED_ACTION");
  }

  @Test
  @DisplayName("a non-fishing semantic target abstains")
  void nonFishingTargetAbstains() {
    when(activeModelService.getActiveModel())
        .thenReturn(modelWithAction(SemanticAction.interactNpc("net", "bank booth")));

    FishingShadowDecision decision =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(decision).isNotNull();
    assertThat(decision.abstentionReason()).isEqualTo("ABSTAIN_UNSUPPORTED_ACTION");
  }

  @Test
  @DisplayName("a target NPC that does not match the prediction abstains")
  void mismatchedTargetNpcAbstains() {
    // The nearest fishing spot is on a different plane than the player, so the predicted target NPC
    // does not match the player's plane and the action must not be recommended.
    List<Map<String, Object>> npcs =
        List.of(Map.of("name", "Fishing spot", "x", 101, "y", 100, "plane", 1));

    FishingShadowDecision decision =
        service.evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, npcs));
    assertThat(decision).isNotNull();
    assertThat(decision.status()).isEqualTo("ABSTAINED");
    assertThat(decision.abstentionReason()).isEqualTo("ABSTAIN_UNSUPPORTED_ACTION");
  }

  @Test
  @DisplayName("the decision model id comes from the snapshot, not a separate fetch")
  void decisionModelIdComesFromSnapshot() {
    // Simulate activation changing concurrently: the separate getActiveModelId() returns a
    // different id than the model actually used for inference.
    when(activeModelService.getActiveModelId()).thenReturn("fishing_changed_concurrently");

    FishingShadowDecision predicted =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(predicted).isNotNull();
    assertThat(predicted.modelId()).isEqualTo("fishing_ab12cd34");

    FishingShadowDecision abstained =
        service.evaluate(
            batch("debug_1", 2, 100, 100, 1, false, true, -1, 1, fishingSpot(101, 100, 1)));
    assertThat(abstained).isNotNull();
    assertThat(abstained.modelId()).isEqualTo("fishing_ab12cd34");
  }

  @Test
  @DisplayName("every abstention reports meaningful latency")
  void abstentionsReportMeaningfulLatency() {
    when(activeModelService.getActiveModel()).thenReturn(null);
    FishingShadowDecision noModel =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(noModel).isNotNull();
    assertThat(noModel.abstentionReason()).isEqualTo("ABSTAIN_NO_ACTIVE_MODEL");
    assertThat(noModel.latencyMicros()).isGreaterThanOrEqualTo(0);

    when(activeModelService.getActiveModel()).thenReturn(model);
    FishingShadowDecision moving =
        service.evaluate(
            batch("debug_1", 2, 100, 100, 0, true, false, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(moving).isNotNull();
    assertThat(moving.abstentionReason()).isEqualTo("ABSTAIN_MOVING");
    assertThat(moving.latencyMicros()).isGreaterThanOrEqualTo(0);

    FishingShadowDecision busy =
        service.evaluate(
            batch("debug_1", 3, 100, 100, 0, false, true, 715, 1, fishingSpot(101, 100, 0)));
    assertThat(busy).isNotNull();
    assertThat(busy.abstentionReason()).isEqualTo("ABSTAIN_BUSY");
    assertThat(busy.latencyMicros()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("status reads are safe during concurrent evaluation")
  void statusReadsSafeDuringConcurrentEvaluation() throws Exception {
    int threads = 4;
    int perThread = 25;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      String session = "debug_" + t;
      futures.add(
          executor.submit(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                for (int i = 1; i <= perThread; i++) {
                  service.evaluate(
                      batch(session, i, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
                  service.status(session);
                }
                return null;
              }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();

    Map<String, Object> status = service.status("debug_0");
    assertThat(status).containsEntry("processed", perThread);
    assertThat(status).containsEntry("predicted", perThread);
    assertThat(status).containsEntry("abstained", 0);
  }

  @Test
  @DisplayName("an invalid live session ID creates no file and returns no decision")
  void invalidSessionIdCreatesNoFileAndNoDecision() throws Exception {
    FishingShadowDecision decision =
        service.evaluate(
            batch("../evil", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(decision).isNull();
    writer.flush();

    Path file = tempDir.resolve("fishing").resolve("shadow").resolve("../evil.jsonl");
    assertThat(Files.exists(file)).isFalse();
    assertThat(Files.exists(tempDir.resolve("evil.jsonl"))).isFalse();

    Map<String, Object> status = service.status("../evil");
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("sessionId", null);
  }

  @Test
  @DisplayName("session cleanup removes baselines and counters")
  void sessionCleanupRemovesState() {
    service.evaluate(
        batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    service.cleanupSession("debug_1");

    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("latestTick", -1L);
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("predicted", 0);
    assertThat(status).containsEntry("abstained", 0);
  }

  @Test
  @DisplayName("history is valid JSONL and path traversal is rejected")
  void historyIsJsonlAndTraversalRejected() throws Exception {
    service.evaluate(
        batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    Path file = tempDir.resolve("fishing").resolve("shadow").resolve("debug_1.jsonl");
    assertThat(Files.exists(file)).isTrue();
    List<String> lines = Files.readAllLines(file);
    assertThat(lines).hasSize(1);
    FishingShadowDecision parsed =
        objectMapper.readValue(lines.get(0), FishingShadowDecision.class);
    assertThat(parsed.sessionId()).isEqualTo("debug_1");
    assertThat(parsed.tick()).isEqualTo(1);

    assertThatThrownBy(() -> service.history("../evil", 50))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> {
              PrototypeApiException ex = (PrototypeApiException) e;
              assertThat(ex.getCode()).isEqualTo("INVALID_SESSION_ID");
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });

    assertThat(service.history("debug_missing", 50)).isEmpty();
  }

  @Test
  @DisplayName("history is returned newest-first and respects the limit")
  void historyIsNewestFirstAndLimited() throws Exception {
    for (long tick = 1; tick <= 60; tick++) {
      service.evaluate(
          batch("debug_1", tick, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    }
    writer.flush();

    List<FishingShadowDecision> defaultHistory = service.history("debug_1", 0);
    assertThat(defaultHistory).hasSize(50);
    assertThat(defaultHistory.get(0).tick()).isEqualTo(60);
    assertThat(defaultHistory.get(49).tick()).isEqualTo(11);

    List<FishingShadowDecision> capped = service.history("debug_1", 1000);
    assertThat(capped).hasSize(60);

    List<FishingShadowDecision> limited = service.history("debug_1", 5);
    assertThat(limited).hasSize(5);
    assertThat(limited.get(0).tick()).isEqualTo(60);
  }

  @Test
  @DisplayName("persistence failure does not stop tick processing")
  void persistenceFailureDoesNotStopProcessing() throws Exception {
    Path blocker = tempDir.resolve("fishing");
    Files.writeString(blocker, "blocker");

    FishingShadowDecision decision =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(decision).isNotNull();
    assertThat(decision.status()).isEqualTo("PREDICTED");

    writer.flush();
    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("processed", 1);
    assertThat(status).containsKey("persistenceError");
    assertThat(status.get("persistenceError")).isNotNull();
  }

  @Test
  @DisplayName(
      "the inventory baseline is captured on the first valid tick even without an active model")
  void baselineEstablishedBeforeActivation() {
    when(activeModelService.getActiveModel()).thenReturn(null);

    // First tick arrives while no model is active: baseline 1 must be captured anyway.
    FishingShadowDecision abstained =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(abstained).isNotNull();
    assertThat(abstained.abstentionReason()).isEqualTo("ABSTAIN_NO_ACTIVE_MODEL");

    // A model becomes active and the inventory now reports 50. The baseline must remain 1, so the
    // delta of 49 matches the catch-progress-4 demonstration (tick 8204), never catch-progress-0.
    when(activeModelService.getActiveModel()).thenReturn(model);
    FishingShadowDecision predicted =
        service.evaluate(
            batch("debug_1", 2, 100, 100, 0, false, true, -1, 50, fishingSpot(101, 100, 0)));
    assertThat(predicted).isNotNull();
    assertThat(predicted.matchedExampleTick()).isEqualTo(8204L);
  }

  @Test
  @DisplayName("history tolerates an incomplete trailing line from an interrupted append")
  void historyToleratesIncompleteTrailingLine() throws Exception {
    Files.createDirectories(tempDir.resolve("fishing").resolve("shadow"));
    Path file = tempDir.resolve("fishing").resolve("shadow").resolve("debug_1.jsonl");
    String line =
        objectMapper.writeValueAsString(
            new FishingShadowDecision(
                1,
                "AI_PLAYER_SHADOW_DECISION",
                "PREDICTED",
                "fishing_ab12cd34",
                "debug_1",
                1,
                "2026-08-11T05:30:00Z",
                NET_FISHING_SPOT,
                null,
                null,
                4.0,
                8201L,
                450));
    Files.writeString(file, line + System.lineSeparator() + line.substring(0, line.length() - 6));

    List<FishingShadowDecision> history = service.history("debug_1", 50);
    assertThat(history).hasSize(1);
    assertThat(history.get(0).tick()).isEqualTo(1);
  }

  @Test
  @DisplayName("history treats a malformed completed line as corruption")
  void historyRejectsMalformedMiddleLine() throws Exception {
    Files.createDirectories(tempDir.resolve("fishing").resolve("shadow"));
    Path file = tempDir.resolve("fishing").resolve("shadow").resolve("debug_1.jsonl");
    String line =
        objectMapper.writeValueAsString(
            new FishingShadowDecision(
                1,
                "AI_PLAYER_SHADOW_DECISION",
                "PREDICTED",
                "fishing_ab12cd34",
                "debug_1",
                1,
                "2026-08-11T05:30:00Z",
                NET_FISHING_SPOT,
                null,
                null,
                4.0,
                8201L,
                450));
    Files.writeString(
        file, line + System.lineSeparator() + "not-json{" + System.lineSeparator() + line);

    assertThatThrownBy(() -> service.history("debug_1", 50))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e ->
                assertThat(((PrototypeApiException) e).getCode()).isEqualTo("HISTORY_READ_FAILED"));
  }

  @Test
  @DisplayName("history never fails while decisions are being appended concurrently")
  void historyDoesNotFailDuringConcurrentAppend() throws Exception {
    int readers = 4;
    ExecutorService executor = Executors.newFixedThreadPool(readers);
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean failed = new AtomicBoolean(false);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < readers; t++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                for (int i = 0; i < 100; i++) {
                  try {
                    service.history("debug_1", 50);
                  } catch (PrototypeApiException e) {
                    failed.set(true);
                  }
                }
                return null;
              }));
    }
    start.countDown();
    for (long tick = 1; tick <= 300; tick++) {
      service.evaluate(
          batch("debug_1", tick, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    }
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();
    writer.flush();

    assertThat(failed).isFalse();
    assertThat(service.history("debug_1", 50)).isNotEmpty();
  }

  @Test
  @DisplayName("repeated concurrent append/read loops never fail history reads")
  void repeatedConcurrentAppendReadLoopsAreSafe() throws Exception {
    int readers = 4;
    ExecutorService executor = Executors.newFixedThreadPool(readers);
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean failed = new AtomicBoolean(false);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < readers; t++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                for (int round = 0; round < 3; round++) {
                  for (int i = 0; i < 200; i++) {
                    try {
                      service.history("debug_1", 50);
                    } catch (PrototypeApiException e) {
                      failed.set(true);
                    }
                  }
                  Thread.yield();
                }
                return null;
              }));
    }
    start.countDown();
    // Multiple append bursts running concurrently with the read loops, each followed by a flush.
    long tick = 1;
    for (int round = 0; round < 3; round++) {
      for (int i = 0; i < 200; i++, tick++) {
        service.evaluate(
            batch("debug_1", tick, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
      }
      writer.flush();
    }
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();
    writer.flush();

    assertThat(failed).isFalse();
    assertThat(service.history("debug_1", 50)).isNotEmpty();
  }

  @Test
  @DisplayName("status reports the activation state/error when no model is active")
  void statusReportsActivationReasonWithoutActiveModel() {
    when(activeModelService.getActiveSnapshot())
        .thenReturn(
            new FishingActiveModelService.ActiveModelInfo(
                null, null, null, "MODEL_NOT_REPLAY_VALIDATED", "MODEL_NOT_REPLAY_VALIDATED"));

    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("readyForInference", false);
    assertThat(status).containsEntry("inactiveReason", "MODEL_NOT_REPLAY_VALIDATED");
    assertThat(status).containsEntry("activationState", "MODEL_NOT_REPLAY_VALIDATED");
    assertThat(status).containsEntry("activationLoadError", "MODEL_NOT_REPLAY_VALIDATED");
    assertThat(status).containsEntry("activeModelId", null);
    assertThat(status).containsEntry("activeModel", null);
  }

  @Test
  @DisplayName("status remains internally consistent while the active model changes concurrently")
  void statusConsistentDuringConcurrentActivation() throws Exception {
    FishingModelSummary summaryA = new FishingModelSummary("fishing_a", "t", "r", 1, true, true);
    FishingModelSummary summaryB = new FishingModelSummary("fishing_b", "t", "r", 1, true, true);
    FishingActiveModelService.ActiveModelInfo infoA =
        new FishingActiveModelService.ActiveModelInfo(model, "fishing_a", summaryA, null, null);
    FishingActiveModelService.ActiveModelInfo infoB =
        new FishingActiveModelService.ActiveModelInfo(model, "fishing_b", summaryB, null, null);
    AtomicReference<FishingActiveModelService.ActiveModelInfo> current =
        new AtomicReference<>(infoA);
    when(activeModelService.getActiveSnapshot()).thenAnswer(inv -> current.get());

    int threads = 4;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean mismatch = new AtomicBoolean(false);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                for (int i = 0; i < 200; i++) {
                  Map<String, Object> status = service.status("debug_1");
                  String id = (String) status.get("activeModelId");
                  Object summary = status.get("activeModel");
                  if (id == null) {
                    if (summary != null) {
                      mismatch.set(true);
                    }
                  } else {
                    if (!(summary instanceof FishingModelSummary s) || !id.equals(s.modelId())) {
                      mismatch.set(true);
                    }
                  }
                }
                return null;
              }));
    }
    futures.add(
        executor.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              for (int i = 0; i < 200; i++) {
                current.set(i % 2 == 0 ? infoA : infoB);
              }
              return null;
            }));
    start.countDown();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertThat(mismatch).isFalse();
  }

  @Test
  @DisplayName("status exposes persistence queue, written, and dropped counters")
  void statusExposesPersistenceCounters() throws Exception {
    service.evaluate(
        batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("persistenceWritten", 1L);
    assertThat(status).containsKey("persistenceQueueSize");
    assertThat(status).containsKey("persistenceDropped");
    assertThat(status).containsKey("persistenceEnqueued");
    assertThat(status).containsKey("persistenceLifecycle");
  }

  @Test
  @DisplayName("cleanup while an evaluation is paused before finalization waits and then closes")
  void cleanupWhileEvaluationPausedBeforeFinalization() throws Exception {
    CountDownLatch enqueueStarted = new CountDownLatch(1);
    CountDownLatch releaseEnqueue = new CountDownLatch(1);
    FishingShadowDecisionWriter blockingWriter =
        new FishingShadowDecisionWriter(objectMapper) {
          @Override
          public boolean enqueue(FishingShadowDecision decision) {
            enqueueStarted.countDown();
            try {
              releaseEnqueue.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return super.enqueue(decision);
          }
        };
    ReflectionTestUtils.setField(blockingWriter, "outputDir", tempDir.toString());
    blockingWriter.start();

    FishingShadowDecisionService svc = serviceWithWriter(blockingWriter);
    AtomicReference<FishingShadowDecision> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch evaluationDone = new CountDownLatch(1);
    Thread evaluator =
        new Thread(
            () -> {
              try {
                result.set(
                    svc.evaluate(
                        batch(
                            "debug_1",
                            1,
                            100,
                            100,
                            0,
                            false,
                            true,
                            -1,
                            1,
                            fishingSpot(101, 100, 0))));
              } catch (Throwable t) {
                failure.set(t);
              } finally {
                evaluationDone.countDown();
              }
            });
    evaluator.start();
    assertThat(enqueueStarted.await(5, TimeUnit.SECONDS)).isTrue();

    // Cleanup starts while the evaluation is paused at the persistence boundary (the last step of
    // finalization). It must block until the evaluation releases the session lock.
    AtomicBoolean cleanupDone = new AtomicBoolean(false);
    Thread cleaner =
        new Thread(
            () -> {
              svc.cleanupSession("debug_1");
              cleanupDone.set(true);
            });
    cleaner.start();
    Thread.sleep(150);
    assertThat(cleanupDone.get()).isFalse();

    releaseEnqueue.countDown();
    assertThat(evaluationDone.await(5, TimeUnit.SECONDS)).isTrue();
    cleaner.join(5000);

    assertThat(failure.get()).isNull();
    assertThat(result.get()).isNotNull();
    assertThat(result.get().status()).isEqualTo("PREDICTED");
    assertThat(cleanupDone.get()).isTrue();
    // The evaluation finished before closure, then cleanup removed the session state.
    Map<String, Object> status = svc.status("debug_1");
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("sessionId", null);
    blockingWriter.stop();
  }

  @Test
  @DisplayName("an evaluation that began before closure cannot finalize after the state is closed")
  void closedStateRejectsLateFinalization() throws Exception {
    FishingShadowDecision first =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(first).isNotNull();
    assertThat(first.status()).isEqualTo("PREDICTED");

    // Simulate closure of the exact state object an in-flight evaluation already resolved: cleanup
    // sets closed=true under the state lock (the map removal is the final step of cleanup).
    Map<String, Object> sessions =
        (Map<String, Object>) ReflectionTestUtils.getField(service, "sessions");
    Object state = sessions.get("debug_1");
    synchronized (state) {
      ReflectionTestUtils.setField(state, "closed", true);
    }

    // An evaluation that began before closure but finalizes after it must be refused: no decision,
    // no counters, no persistence enqueue.
    FishingShadowDecision late =
        service.evaluate(
            batch("debug_1", 2, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(late).isNull();

    writer.flush();
    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("processed", 1);
    assertThat(status).containsEntry("latestTick", 1L);
    assertThat(service.history("debug_1", 50)).hasSize(1);
  }

  @Test
  @DisplayName("a later genuine session with the same ID creates a fresh state generation")
  void freshStateCreatedForLaterSession() throws Exception {
    service.evaluate(
        batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    service.cleanupSession("debug_1");

    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("latestTick", -1L);

    FishingShadowDecision later =
        service.evaluate(
            batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(later).isNotNull();
    assertThat(later.status()).isEqualTo("PREDICTED");
    assertThat(service.status("debug_1")).containsEntry("processed", 1);
    assertThat(service.status("debug_1")).containsEntry("latestTick", 1L);
  }

  @Test
  @DisplayName("cleanup remains idempotent when explicit stop and disconnect both invoke it")
  void cleanupIdempotentAcrossStopAndDisconnect() throws Exception {
    service.evaluate(
        batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));

    service.cleanupSession("debug_1");
    service.cleanupSession("debug_1");
    service.cleanupSession(null);

    Map<String, Object> status = service.status("debug_1");
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("latestTick", -1L);
    assertThat(status).containsEntry("sessionId", null);
  }
}
