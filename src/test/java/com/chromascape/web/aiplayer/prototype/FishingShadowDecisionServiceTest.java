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

/** Tests the live shadow decision service: gates, baselines, persistence, status, and cleanup. */
class FishingShadowDecisionServiceTest {

  private static final SemanticAction NET_FISHING_SPOT =
      SemanticAction.interactNpc("net", "fishing spot");
  private static final String RUN = "run_1";

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

  /**
   * Evaluates through {@link FishingShadowDecisionService#evaluateAndPublish} and returns the
   * published decision (or null when suppressed). The run is opened first, mirroring SESSION_START.
   */
  private FishingShadowDecision evaluate(FishingShadowDecisionService svc, TickBatchMessage batch) {
    return evaluate(svc, batch, RUN);
  }

  private FishingShadowDecision evaluate(
      FishingShadowDecisionService svc, TickBatchMessage batch, String runId) {
    svc.openSession(batch.getSessionId(), runId);
    AtomicReference<FishingShadowDecision> ref = new AtomicReference<>();
    svc.evaluateAndPublish(batch, runId, ref::set);
    return ref.get();
  }

  private FishingShadowDecision evaluate(TickBatchMessage batch) {
    return evaluate(service, batch);
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

  @SuppressWarnings("unchecked")
  private long stateGeneration(String sessionId) {
    Map<FishingShadowDecisionService.ShadowRunKey, Object> sessions =
        (Map<FishingShadowDecisionService.ShadowRunKey, Object>)
            ReflectionTestUtils.getField(service, "sessions");
    Object state = sessions.get(new FishingShadowDecisionService.ShadowRunKey(sessionId, RUN));
    assertThat(state).isNotNull();
    return (long) ReflectionTestUtils.getField(state, "generation");
  }

  @SuppressWarnings("unchecked")
  private Object currentState(String sessionId) {
    Map<FishingShadowDecisionService.ShadowRunKey, Object> sessions =
        (Map<FishingShadowDecisionService.ShadowRunKey, Object>)
            ReflectionTestUtils.getField(service, "sessions");
    return sessions.get(new FishingShadowDecisionService.ShadowRunKey(sessionId, RUN));
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
            evaluate(
                batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0))))
        .isNull();
    assertThat(service.status(null, null)).containsEntry("operational", false);

    setFlags(true, false, false);
    assertThat(service.status(null, null)).containsEntry("operational", false);

    setFlags(true, true, false);
    assertThat(service.status(null, null)).containsEntry("operational", true);
  }

  @Test
  @DisplayName("control enabled prevents inference")
  void controlEnabledPreventsInference() {
    setFlags(true, true, true);

    FishingShadowDecision decision =
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(decision).isNull();
    Map<String, Object> status = service.status("debug_1", RUN);
    assertThat(status).containsEntry("operational", false);
    assertThat(status).containsEntry("inactiveReason", "INACTIVE_CONTROL_ENABLED");
  }

  @Test
  @DisplayName("idle state with a visible matching spot produces INTERACT_NPC")
  void idleStateProducesInteractNpc() {
    FishingShadowDecision decision =
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));

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
        evaluate(batch("debug_1", 1, 100, 100, 0, true, false, -1, 1, fishingSpot(101, 100, 0)));

    assertThat(decision).isNotNull();
    assertThat(decision.status()).isEqualTo("ABSTAINED");
    assertThat(decision.abstentionReason()).isEqualTo("ABSTAIN_MOVING");
    assertThat(decision.action()).isNull();
  }

  @Test
  @DisplayName("animation or non-idle state produces ABSTAIN_BUSY")
  void busyStateAbstains() {
    FishingShadowDecision animated =
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, 715, 1, fishingSpot(101, 100, 0)));
    assertThat(animated.abstentionReason()).isEqualTo("ABSTAIN_BUSY");

    FishingShadowDecision nonIdle =
        evaluate(batch("debug_1", 2, 100, 100, 0, false, false, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(nonIdle.abstentionReason()).isEqualTo("ABSTAIN_BUSY");
  }

  @Test
  @DisplayName("missing spot, plane mismatch, and excessive distance preserve their reasons")
  void policyGatesPreserveReasons() {
    FishingShadowDecision missingSpot =
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, List.of()));
    assertThat(missingSpot.abstentionReason()).isEqualTo("ABSTAIN_TARGET_MISSING");

    FishingShadowDecision planeMismatch =
        evaluate(batch("debug_1", 2, 100, 100, 1, false, true, -1, 1, fishingSpot(101, 100, 1)));
    assertThat(planeMismatch.abstentionReason()).isEqualTo("ABSTAIN_PLANE_MISMATCH");

    FishingShadowDecision tooFar =
        evaluate(batch("debug_1", 3, 120, 100, 0, false, true, -1, 1, fishingSpot(121, 100, 0)));
    assertThat(tooFar.abstentionReason()).isEqualTo("ABSTAIN_TOO_FAR");
  }

  @Test
  @DisplayName("missing active model produces ABSTAIN_NO_ACTIVE_MODEL")
  void noActiveModelAbstains() {
    when(activeModelService.getActiveModel()).thenReturn(null);

    FishingShadowDecision decision =
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(decision).isNotNull();
    assertThat(decision.abstentionReason()).isEqualTo("ABSTAIN_NO_ACTIVE_MODEL");
  }

  @Test
  @DisplayName("missing required state produces ABSTAIN_INVALID_STATE")
  void missingRequiredStateAbstains() {
    TickBatchMessage batch =
        batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0));
    batch.setPlayerState(null);

    FishingShadowDecision decision = evaluate(batch);
    assertThat(decision).isNotNull();
    assertThat(decision.abstentionReason()).isEqualTo("ABSTAIN_INVALID_STATE");
  }

  @Test
  @DisplayName("duplicate and out-of-order ticks do not corrupt latest state or history")
  void duplicateAndOutOfOrderTicksIgnored() throws Exception {
    evaluate(batch("debug_1", 100, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(
            evaluate(
                batch("debug_1", 100, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0))))
        .isNull();
    assertThat(
            evaluate(
                batch("debug_1", 99, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0))))
        .isNull();
    evaluate(batch("debug_1", 101, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    Map<String, Object> status = service.status("debug_1", RUN);
    assertThat(status).containsEntry("latestTick", 101L);
    assertThat(status).containsEntry("processed", 2);
    assertThat(status).containsEntry("predicted", 2);

    List<FishingShadowDecision> history = service.history("debug_1", RUN, false, 50);
    assertThat(history).hasSize(2);
    assertThat(history.get(0).tick()).isEqualTo(101);
    assertThat(history.get(1).tick()).isEqualTo(100);
  }

  @Test
  @DisplayName("a duplicate first tick cannot change the inventory baseline")
  void duplicateFirstTickCannotChangeBaseline() {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(
            evaluate(
                batch("debug_1", 1, 100, 100, 0, false, true, -1, 50, fishingSpot(101, 100, 0))))
        .isNull();

    // With the original baseline of 1 and a total of 50 the delta is 49, so the policy must match
    // the catch-progress-4 demonstration (tick 8204). A baseline wrongly moved to 50 would match
    // the catch-progress-0 demonstration (tick 8200).
    FishingShadowDecision decision =
        evaluate(batch("debug_1", 2, 100, 100, 0, false, true, -1, 50, fishingSpot(101, 100, 0)));
    assertThat(decision).isNotNull();
    assertThat(decision.matchedExampleTick()).isEqualTo(8204L);

    Map<String, Object> status = service.status("debug_1", RUN);
    assertThat(status).containsEntry("processed", 2);
  }

  @Test
  @DisplayName("an out-of-order tick cannot change counters or the latest decision")
  void outOfOrderTickCannotChangeCountersOrLatest() throws Exception {
    FishingShadowDecision first =
        evaluate(batch("debug_1", 100, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(
            evaluate(
                batch("debug_1", 99, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0))))
        .isNull();
    writer.flush();

    Map<String, Object> status = service.status("debug_1", RUN);
    assertThat(status).containsEntry("latestTick", 100L);
    assertThat(status).containsEntry("processed", 1);
    assertThat(status).containsEntry("predicted", 1);
    assertThat(status).containsEntry("latestDecision", first);

    List<FishingShadowDecision> history = service.history("debug_1", RUN, false, 50);
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

    FishingShadowDecision decision = evaluate(batch);
    assertThat(decision).isNotNull();
    assertThat(decision.status()).isEqualTo("PREDICTED");
  }

  @Test
  @DisplayName("an unsupported fishing option abstains")
  void unsupportedOptionAbstains() {
    when(activeModelService.getActiveModel())
        .thenReturn(modelWithAction(SemanticAction.interactNpc("bomb", "fishing spot")));

    FishingShadowDecision decision =
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
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
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
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
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, npcs));
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
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(predicted).isNotNull();
    assertThat(predicted.modelId()).isEqualTo("fishing_ab12cd34");

    FishingShadowDecision abstained =
        evaluate(batch("debug_1", 2, 100, 100, 1, false, true, -1, 1, fishingSpot(101, 100, 1)));
    assertThat(abstained).isNotNull();
    assertThat(abstained.modelId()).isEqualTo("fishing_ab12cd34");
  }

  @Test
  @DisplayName("every abstention reports meaningful latency")
  void abstentionsReportMeaningfulLatency() {
    when(activeModelService.getActiveModel()).thenReturn(null);
    FishingShadowDecision noModel =
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(noModel).isNotNull();
    assertThat(noModel.abstentionReason()).isEqualTo("ABSTAIN_NO_ACTIVE_MODEL");
    assertThat(noModel.latencyMicros()).isGreaterThanOrEqualTo(0);

    when(activeModelService.getActiveModel()).thenReturn(model);
    FishingShadowDecision moving =
        evaluate(batch("debug_1", 2, 100, 100, 0, true, false, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(moving).isNotNull();
    assertThat(moving.abstentionReason()).isEqualTo("ABSTAIN_MOVING");
    assertThat(moving.latencyMicros()).isGreaterThanOrEqualTo(0);

    FishingShadowDecision busy =
        evaluate(batch("debug_1", 3, 100, 100, 0, false, true, 715, 1, fishingSpot(101, 100, 0)));
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
                  evaluate(
                      batch(session, i, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
                  service.status(session, RUN);
                }
                return null;
              }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();

    Map<String, Object> status = service.status("debug_0", RUN);
    assertThat(status).containsEntry("processed", perThread);
    assertThat(status).containsEntry("predicted", perThread);
    assertThat(status).containsEntry("abstained", 0);
  }

  @Test
  @DisplayName("an invalid live session ID creates no file and returns no decision")
  void invalidSessionIdCreatesNoFileAndNoDecision() throws Exception {
    FishingShadowDecision decision =
        evaluate(batch("../evil", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(decision).isNull();
    writer.flush();

    Path file = tempDir.resolve("fishing").resolve("shadow").resolve("../evil.jsonl");
    assertThat(Files.exists(file)).isFalse();
    assertThat(Files.exists(tempDir.resolve("evil.jsonl"))).isFalse();

    Map<String, Object> status = service.status("../evil", RUN);
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("sessionId", null);
  }

  @Test
  @DisplayName("session cleanup removes baselines and counters")
  void sessionCleanupRemovesState() {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    service.cleanupSession("debug_1", RUN);

    Map<String, Object> status = service.status("debug_1", RUN);
    assertThat(status).containsEntry("latestTick", -1L);
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("predicted", 0);
    assertThat(status).containsEntry("abstained", 0);
  }

  @Test
  @DisplayName("history is valid JSONL and path traversal is rejected")
  void historyIsJsonlAndTraversalRejected() throws Exception {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    Path file =
        tempDir.resolve("fishing").resolve("shadow").resolve("debug_1").resolve(RUN + ".jsonl");
    assertThat(Files.exists(file)).isTrue();
    List<String> lines = Files.readAllLines(file);
    assertThat(lines).hasSize(1);
    FishingShadowDecision parsed =
        objectMapper.readValue(lines.get(0), FishingShadowDecision.class);
    assertThat(parsed.sessionId()).isEqualTo("debug_1");
    assertThat(parsed.shadowRunId()).isEqualTo(RUN);
    assertThat(parsed.tick()).isEqualTo(1);

    assertThatThrownBy(() -> service.history("../evil", RUN, false, 50))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> {
              PrototypeApiException ex = (PrototypeApiException) e;
              assertThat(ex.getCode()).isEqualTo("INVALID_SESSION_ID");
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });

    assertThat(service.history("debug_missing", RUN, false, 50)).isEmpty();
  }

  @Test
  @DisplayName("history is returned newest-first and respects the limit")
  void historyIsNewestFirstAndLimited() throws Exception {
    for (long tick = 1; tick <= 60; tick++) {
      evaluate(batch("debug_1", tick, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    }
    writer.flush();

    List<FishingShadowDecision> defaultHistory = service.history("debug_1", RUN, false, 0);
    assertThat(defaultHistory).hasSize(50);
    assertThat(defaultHistory.get(0).tick()).isEqualTo(60);
    assertThat(defaultHistory.get(49).tick()).isEqualTo(11);

    List<FishingShadowDecision> capped = service.history("debug_1", RUN, false, 1000);
    assertThat(capped).hasSize(60);

    List<FishingShadowDecision> limited = service.history("debug_1", RUN, false, 5);
    assertThat(limited).hasSize(5);
    assertThat(limited.get(0).tick()).isEqualTo(60);
  }

  @Test
  @DisplayName("persistence failure does not stop tick processing")
  void persistenceFailureDoesNotStopProcessing() throws Exception {
    Path blocker = tempDir.resolve("fishing");
    Files.writeString(blocker, "blocker");

    FishingShadowDecision decision =
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(decision).isNotNull();
    assertThat(decision.status()).isEqualTo("PREDICTED");

    writer.flush();
    Map<String, Object> status = service.status("debug_1", RUN);
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
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(abstained).isNotNull();
    assertThat(abstained.abstentionReason()).isEqualTo("ABSTAIN_NO_ACTIVE_MODEL");

    // A model becomes active and the inventory now reports 50. The baseline must remain 1, so the
    // delta of 49 matches the catch-progress-4 demonstration (tick 8204), never catch-progress-0.
    when(activeModelService.getActiveModel()).thenReturn(model);
    FishingShadowDecision predicted =
        evaluate(batch("debug_1", 2, 100, 100, 0, false, true, -1, 50, fishingSpot(101, 100, 0)));
    assertThat(predicted).isNotNull();
    assertThat(predicted.matchedExampleTick()).isEqualTo(8204L);
  }

  @Test
  @DisplayName("history tolerates an incomplete trailing line from an interrupted append")
  void historyToleratesIncompleteTrailingLine() throws Exception {
    Path file =
        tempDir.resolve("fishing").resolve("shadow").resolve("debug_1").resolve(RUN + ".jsonl");
    Files.createDirectories(file.getParent());
    String line =
        objectMapper.writeValueAsString(
            new FishingShadowDecision(
                2,
                "AI_PLAYER_SHADOW_DECISION",
                "PREDICTED",
                "fishing_ab12cd34",
                "debug_1",
                RUN,
                1,
                "2026-08-11T05:30:00Z",
                NET_FISHING_SPOT,
                null,
                null,
                4.0,
                8201L,
                450));
    Files.writeString(file, line + System.lineSeparator() + line.substring(0, line.length() - 6));

    List<FishingShadowDecision> history = service.history("debug_1", RUN, false, 50);
    assertThat(history).hasSize(1);
    assertThat(history.get(0).tick()).isEqualTo(1);
  }

  @Test
  @DisplayName("history treats a malformed completed line as corruption")
  void historyRejectsMalformedMiddleLine() throws Exception {
    Path file =
        tempDir.resolve("fishing").resolve("shadow").resolve("debug_1").resolve(RUN + ".jsonl");
    Files.createDirectories(file.getParent());
    String line =
        objectMapper.writeValueAsString(
            new FishingShadowDecision(
                2,
                "AI_PLAYER_SHADOW_DECISION",
                "PREDICTED",
                "fishing_ab12cd34",
                "debug_1",
                RUN,
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

    assertThatThrownBy(() -> service.history("debug_1", RUN, false, 50))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e ->
                assertThat(((PrototypeApiException) e).getCode()).isEqualTo("HISTORY_READ_FAILED"));
  }

  @Test
  @DisplayName("run discovery tolerates an incomplete trailing line from an interrupted append")
  void runsToleratesIncompleteTrailingLine() throws Exception {
    Path file =
        tempDir.resolve("fishing").resolve("shadow").resolve("debug_1").resolve(RUN + ".jsonl");
    Files.createDirectories(file.getParent());
    String line =
        objectMapper.writeValueAsString(
            new FishingShadowDecision(
                2,
                "AI_PLAYER_SHADOW_DECISION",
                "PREDICTED",
                "fishing_ab12cd34",
                "debug_1",
                RUN,
                1,
                "2026-08-11T05:30:00Z",
                NET_FISHING_SPOT,
                null,
                null,
                4.0,
                8201L,
                450));
    Files.writeString(file, line + System.lineSeparator() + line.substring(0, line.length() - 6));

    Map<String, Object> runs = service.runs("debug_1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runList = (List<Map<String, Object>>) runs.get("runs");
    assertThat(runList).hasSize(1);
    assertThat(runList.get(0).get("shadowRunId")).isEqualTo(RUN);
    assertThat(runList.get(0).get("decisionCount")).isEqualTo(1);
    assertThat(runList.get(0).get("lastTick")).isEqualTo(1L);
  }

  @Test
  @DisplayName("run discovery treats a malformed completed line as corruption")
  void runsRejectsMalformedMiddleLine() throws Exception {
    Path file =
        tempDir.resolve("fishing").resolve("shadow").resolve("debug_1").resolve(RUN + ".jsonl");
    Files.createDirectories(file.getParent());
    String line =
        objectMapper.writeValueAsString(
            new FishingShadowDecision(
                2,
                "AI_PLAYER_SHADOW_DECISION",
                "PREDICTED",
                "fishing_ab12cd34",
                "debug_1",
                RUN,
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

    assertThatThrownBy(() -> service.runs("debug_1"))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e ->
                assertThat(((PrototypeApiException) e).getCode()).isEqualTo("HISTORY_READ_FAILED"));
  }

  @Test
  @DisplayName("a persisted decision carrying the legacy control_allowed field still parses")
  void historyAcceptsLegacyControlAllowedField() throws Exception {
    Path file =
        tempDir.resolve("fishing").resolve("shadow").resolve("debug_1").resolve(RUN + ".jsonl");
    Files.createDirectories(file.getParent());
    String line =
        objectMapper.writeValueAsString(
                new FishingShadowDecision(
                    2,
                    "AI_PLAYER_SHADOW_DECISION",
                    "PREDICTED",
                    "fishing_ab12cd34",
                    "debug_1",
                    RUN,
                    1,
                    "2026-08-11T05:30:00Z",
                    NET_FISHING_SPOT,
                    null,
                    null,
                    4.0,
                    8201L,
                    450))
            + System.lineSeparator();
    // Simulate a legacy producer that wrote control_allowed=true. The field is read-only on the
    // record: it must parse safely and the decision must never report control allowed.
    Files.writeString(file, line.replace("\"control_allowed\":false", "\"control_allowed\":true"));

    List<FishingShadowDecision> history = service.history("debug_1", RUN, false, 50);
    assertThat(history).hasSize(1);
    assertThat(history.get(0).tick()).isEqualTo(1);
    assertThat(history.get(0).controlAllowed()).isFalse();
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
                    service.history("debug_1", RUN, false, 50);
                  } catch (PrototypeApiException e) {
                    failed.set(true);
                  }
                }
                return null;
              }));
    }
    start.countDown();
    for (long tick = 1; tick <= 300; tick++) {
      evaluate(batch("debug_1", tick, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    }
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();
    writer.flush();

    assertThat(failed).isFalse();
    assertThat(service.history("debug_1", RUN, false, 50)).isNotEmpty();
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
                      service.history("debug_1", RUN, false, 50);
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
        evaluate(batch("debug_1", tick, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
      }
      writer.flush();
    }
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();
    writer.flush();

    assertThat(failed).isFalse();
    assertThat(service.history("debug_1", RUN, false, 50)).isNotEmpty();
  }

  @Test
  @DisplayName("status reports the activation state/error when no model is active")
  void statusReportsActivationReasonWithoutActiveModel() {
    when(activeModelService.getActiveSnapshot())
        .thenReturn(
            new FishingActiveModelService.ActiveModelInfo(
                null, null, null, "MODEL_NOT_REPLAY_VALIDATED", "MODEL_NOT_REPLAY_VALIDATED"));

    Map<String, Object> status = service.status("debug_1", RUN);
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
                  Map<String, Object> status = service.status("debug_1", RUN);
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
  @DisplayName("status exposes the extended persistence counters")
  void statusExposesPersistenceCounters() throws Exception {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    Map<String, Object> status = service.status("debug_1", RUN);
    assertThat(status).containsEntry("persistenceWritten", 1L);
    assertThat(status).containsEntry("persistenceCompleted", 1L);
    assertThat(status).containsEntry("persistenceFailed", 0L);
    assertThat(status).containsEntry("persistenceDropped", 0L);
    assertThat(status).containsEntry("persistenceQueued", 0L);
    assertThat(status).containsEntry("persistenceInFlight", 0L);
    assertThat(status).containsEntry("persistenceEnqueued", 1L);
    assertThat(status).containsEntry("persistenceLifecycle", "RUNNING");
    assertThat(status).containsKey("persistenceQueueSize");
    assertThat(status).containsKey("persistenceError");
  }

  @Test
  @DisplayName("status without a session id reports no inferred session state")
  void statusWithoutSessionReportsNoInferredSession() throws Exception {
    evaluate(batch("debug_1", 5, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    Map<String, Object> status = service.status(null, null);
    assertThat(status).containsEntry("sessionId", null);
    assertThat(status).containsEntry("latestTick", -1L);
    assertThat(status).containsEntry("latestDecision", null);
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("predicted", 0);
    assertThat(status).containsEntry("abstained", 0);
  }

  @Test
  @DisplayName("status for an explicit session returns only that exact active session")
  void statusForExplicitSession() throws Exception {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    evaluate(batch("debug_2", 9, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    Map<String, Object> status = service.status("debug_1", RUN);
    assertThat(status).containsEntry("sessionId", "debug_1");
    assertThat(status).containsEntry("latestTick", 1L);
    assertThat(status).containsEntry("processed", 1);

    Map<String, Object> missing = service.status("debug_never", RUN);
    assertThat(missing).containsEntry("sessionId", null);
    assertThat(missing).containsEntry("latestTick", -1L);
    assertThat(missing).containsEntry("processed", 0);
  }

  @Test
  @DisplayName("publication holding the session lock makes cleanup wait")
  void publicationHoldingSessionLockMakesCleanupWait() throws Exception {
    CountDownLatch publicationStarted = new CountDownLatch(1);
    CountDownLatch releasePublication = new CountDownLatch(1);
    AtomicReference<FishingShadowDecision> published = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch evaluationDone = new CountDownLatch(1);
    Thread evaluator =
        new Thread(
            () -> {
              try {
                service.openSession("debug_1", RUN);
                service.evaluateAndPublish(
                    batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)),
                    RUN,
                    decision -> {
                      published.set(decision);
                      publicationStarted.countDown();
                      try {
                        releasePublication.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    });
              } catch (Throwable t) {
                failure.set(t);
              } finally {
                evaluationDone.countDown();
              }
            });
    evaluator.start();
    assertThat(publicationStarted.await(5, TimeUnit.SECONDS)).isTrue();

    // Cleanup starts while the publisher is still running under the session lock; it must block
    // until the publication completes.
    AtomicBoolean cleanupDone = new AtomicBoolean(false);
    Thread cleaner =
        new Thread(
            () -> {
              service.cleanupSession("debug_1", RUN);
              cleanupDone.set(true);
            });
    cleaner.start();
    Thread.sleep(150);
    assertThat(cleanupDone.get()).isFalse();

    releasePublication.countDown();
    assertThat(evaluationDone.await(5, TimeUnit.SECONDS)).isTrue();
    cleaner.join(5000);

    assertThat(failure.get()).isNull();
    assertThat(published.get()).isNotNull();
    assertThat(published.get().status()).isEqualTo("PREDICTED");
    assertThat(cleanupDone.get()).isTrue();
    // The publication won, then cleanup closed the session and removed its state.
    Map<String, Object> status = service.status("debug_1", RUN);
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("sessionId", null);
  }

  @Test
  @DisplayName("cleanup completing first suppresses publication, counters, and enqueue")
  void cleanupWinningSuppressesPublicationCountersAndEnqueue() {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    long enqueuedBefore = writer.getEnqueuedCount();

    // Simulate a cleanup that completed between an evaluation resolving this state and that
    // evaluation finalizing: mark the exact resolved state closed, exactly as cleanup does under
    // the state lock.
    Object state = currentState("debug_1");
    synchronized (state) {
      ReflectionTestUtils.setField(state, "closed", true);
    }

    AtomicBoolean published = new AtomicBoolean(false);
    service.evaluateAndPublish(
        batch("debug_1", 2, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)),
        RUN,
        decision -> published.set(true));

    assertThat(published.get()).isFalse();
    // The closed run no longer reports session fields; its counters are suppressed.
    Map<String, Object> status = service.status("debug_1", RUN);
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("latestTick", -1L);
    assertThat(writer.getEnqueuedCount()).isEqualTo(enqueuedBefore);
  }

  @Test
  @DisplayName("reusing a session id obtains a different generation")
  void reusedSessionIdObtainsDifferentGeneration() {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    long first = stateGeneration("debug_1");
    service.cleanupSession("debug_1", RUN);
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    long second = stateGeneration("debug_1");

    assertThat(first).isNotEqualTo(second);
    assertThat(second).isGreaterThan(first);
  }

  @Test
  @DisplayName("an evaluation resolved against a closed generation cannot publish into the reuse")
  void closedGenerationCannotPublishIntoReusedSession() {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    Object oldState = currentState("debug_1");
    service.cleanupSession("debug_1", RUN);
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));

    // A stale evaluation that resolved the closed first-generation state must not publish even if
    // it is the state the session map would hand to a resolver.
    AtomicBoolean published = new AtomicBoolean(false);
    synchronized (oldState) {
      Map<FishingShadowDecisionService.ShadowRunKey, Object> sessions =
          (Map<FishingShadowDecisionService.ShadowRunKey, Object>)
              ReflectionTestUtils.getField(service, "sessions");
      sessions.put(new FishingShadowDecisionService.ShadowRunKey("debug_1", RUN), oldState);
      service.evaluateAndPublish(
          batch("debug_1", 2, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)),
          RUN,
          decision -> published.set(true));
    }
    assertThat(published.get()).isFalse();
  }

  @Test
  @DisplayName("cleanup remains idempotent when explicit stop and disconnect both invoke it")
  void cleanupIdempotentAcrossStopAndDisconnect() throws Exception {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));

    service.cleanupSession("debug_1", RUN);
    service.cleanupSession("debug_1", RUN);
    service.cleanupSession(null, null);

    Map<String, Object> status = service.status("debug_1", RUN);
    assertThat(status).containsEntry("processed", 0);
    assertThat(status).containsEntry("latestTick", -1L);
    assertThat(status).containsEntry("sessionId", null);
  }

  @Test
  @DisplayName("the baseline is reset for the new generation of a reused session id")
  void baselineResetForNewGeneration() {
    // First generation captures baseline 1.
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    service.cleanupSession("debug_1", RUN);

    // Second generation captures baseline 50 on its own first tick; a delta of 0 matches
    // catch-progress-0 (tick 8200), never the first generation's baseline.
    FishingShadowDecision decision =
        evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 50, fishingSpot(101, 100, 0)));
    assertThat(decision).isNotNull();
    assertThat(decision.matchedExampleTick()).isEqualTo(8200L);
    assertThat(service.status("debug_1", RUN)).containsEntry("processed", 1);
  }

  @Test
  @DisplayName("duplicate and stale ticks remain ignored after cleanup")
  void duplicateAndStaleTicksRemainIgnored() throws Exception {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    service.cleanupSession("debug_1", RUN);

    // A duplicate tick on a fresh generation is ignored once that generation processed it.
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    assertThat(
            evaluate(
                batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0))))
        .isNull();
    writer.flush();
    // The persisted file accumulates per textual session id: one line per generation.
    assertThat(service.history("debug_1", RUN, false, 50)).hasSize(2);
    assertThat(service.status("debug_1", RUN)).containsEntry("processed", 1);
  }

  @Test
  @DisplayName("run discovery survives a newly opened run before its first tick")
  void runsSurviveNewlyOpenedRunBeforeFirstTick() {
    service.openSession("debug_1", RUN);

    Map<String, Object> runs = service.runs("debug_1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runList = (List<Map<String, Object>>) runs.get("runs");
    assertThat(runList).hasSize(1);
    Map<String, Object> summary = runList.get(0);
    assertThat(summary).containsEntry("shadowRunId", RUN);
    assertThat(summary).containsEntry("firstObservedAt", null);
    assertThat(summary).containsEntry("lastObservedAt", null);
    assertThat(summary).containsEntry("firstTick", -1L);
    assertThat(summary).containsEntry("lastTick", -1L);
    assertThat(summary).containsEntry("decisionCount", 0);
    assertThat(summary).containsEntry("active", true);
    assertThat(summary).containsEntry("legacy", false);
  }

  @Test
  @DisplayName("run discovery includes only the legacy file with nullable fields")
  void runsIncludeLegacyFileWithNullableFields() throws Exception {
    // Legacy schema-v1 file exists but contains no decisions (empty).
    Path legacy = tempDir.resolve("fishing").resolve("shadow").resolve("debug_1.jsonl");
    Files.createDirectories(legacy.getParent());
    Files.writeString(legacy, "");

    Map<String, Object> runs = service.runs("debug_1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runList = (List<Map<String, Object>>) runs.get("runs");
    assertThat(runList).hasSize(1);
    Map<String, Object> summary = runList.get(0);
    assertThat(summary).containsEntry("shadowRunId", null);
    assertThat(summary).containsEntry("firstObservedAt", null);
    assertThat(summary).containsEntry("lastObservedAt", null);
    assertThat(summary).containsEntry("firstTick", -1L);
    assertThat(summary).containsEntry("lastTick", -1L);
    assertThat(summary).containsEntry("decisionCount", 0);
    assertThat(summary).containsEntry("active", false);
    assertThat(summary).containsEntry("legacy", true);
  }

  @Test
  @DisplayName("run discovery tolerates an empty run file")
  void runsTolerateEmptyRunFile() throws Exception {
    Path file =
        tempDir.resolve("fishing").resolve("shadow").resolve("debug_1").resolve(RUN + ".jsonl");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "");

    Map<String, Object> runs = service.runs("debug_1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runList = (List<Map<String, Object>>) runs.get("runs");
    assertThat(runList).hasSize(1);
    Map<String, Object> summary = runList.get(0);
    assertThat(summary).containsEntry("shadowRunId", RUN);
    assertThat(summary).containsEntry("decisionCount", 0);
    assertThat(summary).containsEntry("legacy", false);
  }

  @Test
  @DisplayName("run discovery merges an active run with its historical file and sorts newest first")
  void runsMergeActiveWithHistoricalAndSortNewestFirst() throws Exception {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    // A historical run file without in-memory state, observed earlier than the active run.
    Path histFile =
        tempDir.resolve("fishing").resolve("shadow").resolve("debug_1").resolve("run_old.jsonl");
    Files.writeString(
        histFile,
        objectMapper.writeValueAsString(decision("debug_1", "run_old", 5, 9, "2026-08-11T04:00:00Z"))
            + System.lineSeparator());

    Map<String, Object> runs = service.runs("debug_1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runList = (List<Map<String, Object>>) runs.get("runs");
    assertThat(runList).hasSize(2);
    // Newest first: the active run processed a later decision than the historical run.
    assertThat(runList.get(0)).containsEntry("shadowRunId", RUN);
    assertThat(runList.get(0)).containsEntry("active", true);
    assertThat(runList.get(0)).containsEntry("decisionCount", 1);
    assertThat(runList.get(1)).containsEntry("shadowRunId", "run_old");
    assertThat(runList.get(1)).containsEntry("active", false);
    assertThat(runList.get(1)).containsEntry("decisionCount", 1);
  }

  @Test
  @DisplayName("run discovery includes the legacy file alongside active and historical runs")
  void runsIncludeLegacyAlongsideActiveAndHistorical() throws Exception {
    evaluate(batch("debug_1", 1, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    Path legacy = tempDir.resolve("fishing").resolve("shadow").resolve("debug_1.jsonl");
    Files.writeString(
        legacy,
        objectMapper.writeValueAsString(decision("debug_1", null, 7, 9))
            + System.lineSeparator());

    Map<String, Object> runs = service.runs("debug_1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runList = (List<Map<String, Object>>) runs.get("runs");
    assertThat(runList).hasSize(2);
    boolean legacyFound = runList.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("legacy")));
    assertThat(legacyFound).isTrue();
    Map<String, Object> legacySummary =
        runList.stream().filter(r -> Boolean.TRUE.equals(r.get("legacy"))).findFirst().orElseThrow();
    assertThat(legacySummary).containsEntry("shadowRunId", null);
    assertThat(legacySummary).containsEntry("legacy", true);
  }

  @Test
  @DisplayName("run summaries with nullable timestamps serialize cleanly through the controller mapper")
  void nullableRunSummariesSerializeCleanly() throws Exception {
    service.openSession("debug_1", RUN);

    Map<String, Object> runs = service.runs("debug_1");
    String json = objectMapper.writeValueAsString(runs);
    assertThat(json).contains("\"shadowRunId\":\"" + RUN + "\"");
    assertThat(json).contains("\"firstObservedAt\":null");
    assertThat(json).contains("\"lastObservedAt\":null");
    assertThat(json).contains("\"firstTick\":-1");
    assertThat(json).contains("\"decisionCount\":0");
  }

  @Test
  @DisplayName(
      "old-run cleanup cannot close a newer run and tick zero is accepted with no history merge")
  void oldRunCleanupCannotAffectNewerRun() throws Exception {
    // Run A (session debug_1, run_a) processes a high tick.
    FishingShadowDecision runA =
        evaluate(
            service,
            batch("debug_1", 500, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)),
            "run_a");
    assertThat(runA).isNotNull();
    writer.flush();

    // Clean up run A.
    service.cleanupSession("debug_1", "run_a");

    // Run B reuses the same textual session id with a fresh shadow run id.
    assertThat(service.openSession("debug_1", "run_b")).isTrue();
    FishingShadowDecision runB =
        evaluate(
            service,
            batch("debug_1", 0, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)),
            "run_b");
    assertThat(runB).isNotNull();
    assertThat(runB.tick()).isEqualTo(0);
    writer.flush();

    // Run B status reflects only run-B state: no run-A decisions.
    Map<String, Object> statusB = service.status("debug_1", "run_b");
    assertThat(statusB).containsEntry("sessionId", "debug_1");
    assertThat(statusB).containsEntry("shadowRunId", "run_b");
    assertThat(statusB).containsEntry("latestTick", 0L);
    assertThat(statusB).containsEntry("processed", 1);

    // Run-B history contains no run-A decision.
    List<FishingShadowDecision> historyB = service.history("debug_1", "run_b", false, 50);
    assertThat(historyB).hasSize(1);
    assertThat(historyB.get(0).tick()).isEqualTo(0);
    assertThat(historyB.get(0).shadowRunId()).isEqualTo("run_b");
  }

  @Test
  @DisplayName("the first in-memory decision supplies firstTick and firstObservedAt")
  void firstInMemoryDecisionSuppliesFirstMetadata() {
    evaluate(batch("debug_1", 10, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));

    Map<String, Object> runs = service.runs("debug_1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runList = (List<Map<String, Object>>) runs.get("runs");
    Map<String, Object> summary = runList.get(0);
    assertThat(summary).containsEntry("firstTick", 10L);
    assertThat(summary).containsEntry("lastTick", 10L);
    assertThat(summary).containsEntry("firstObservedAt", "2026-08-11T05:30:00Z");
    assertThat(summary).containsEntry("decisionCount", 1);
  }

  @Test
  @DisplayName("a later decision changes only the last metadata")
  void laterDecisionChangesOnlyLastMetadata() {
    evaluate(batch("debug_1", 10, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    evaluate(batch("debug_1", 20, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));

    Map<String, Object> runs = service.runs("debug_1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runList = (List<Map<String, Object>>) runs.get("runs");
    Map<String, Object> summary = runList.get(0);
    assertThat(summary).containsEntry("firstTick", 10L);
    assertThat(summary).containsEntry("lastTick", 20L);
    assertThat(summary).containsEntry("firstObservedAt", "2026-08-11T05:30:00Z");
    assertThat(summary).containsEntry("decisionCount", 2);
  }

  @Test
  @DisplayName("persisted and in-memory ranges merge: first is minimum, last and count are maximum")
  void persistedAndInMemoryRangesMerge() throws Exception {
    // Process tick 10 and flush, so the file holds [10].
    evaluate(batch("debug_1", 10, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));
    writer.flush();

    // Process a later tick 20 without flushing, so it is only in memory.
    evaluate(batch("debug_1", 20, 100, 100, 0, false, true, -1, 1, fishingSpot(101, 100, 0)));

    Map<String, Object> runs = service.runs("debug_1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runList = (List<Map<String, Object>>) runs.get("runs");
    Map<String, Object> summary = runList.get(0);
    assertThat(summary).containsEntry("firstTick", 10L);
    assertThat(summary).containsEntry("lastTick", 20L);
    assertThat(summary).containsEntry("decisionCount", 2);
  }

  private FishingShadowDecision decision(
      String sessionId, String shadowRunId, long tick, int statusOrdinal) {
    return decision(sessionId, shadowRunId, tick, statusOrdinal, "2026-08-11T05:30:00Z");
  }

  private FishingShadowDecision decision(
      String sessionId, String shadowRunId, long tick, int statusOrdinal, String observedAt) {
    return new FishingShadowDecision(
        2,
        "AI_PLAYER_SHADOW_DECISION",
        statusOrdinal % 2 == 0 ? "PREDICTED" : "ABSTAINED",
        "fishing_ab12cd34",
        sessionId,
        shadowRunId,
        tick,
        observedAt,
        NET_FISHING_SPOT,
        null,
        null,
        4.0,
        8201L,
        450);
  }
}
