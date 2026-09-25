package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.chromascape.web.aiplayer.prototype.FishingState.InventoryFeature;
import com.chromascape.web.aiplayer.prototype.FishingState.NearbyNpc;
import com.chromascape.web.aiplayer.prototype.FishingState.PlayerFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Tests explicit active-model management and restart restoration. */
class FishingActiveModelServiceTest {

  private static final SemanticAction NET_FISHING_SPOT =
      SemanticAction.interactNpc("net", "fishing spot");

  @TempDir Path tempDir;

  private ObjectMapper objectMapper;
  private FishingDatasetBuilderService modelStore;
  private Path modelsDir;
  private Path runsDir;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    modelStore =
        new FishingDatasetBuilderService(
            mock(FishingPreflightService.class), mock(FishingRecordingLoader.class), objectMapper);
    ReflectionTestUtils.setField(modelStore, "outputDir", tempDir.toString());
    modelsDir = tempDir.resolve("fishing").resolve("models");
    runsDir = tempDir.resolve("fishing").resolve("runs");
  }

  private FishingActiveModelService newService() {
    FishingActiveModelService service = new FishingActiveModelService(modelStore, objectMapper);
    ReflectionTestUtils.setField(service, "outputDir", tempDir.toString());
    return service;
  }

  private FishingState stateAt(int cp) {
    PlayerFeature player = new PlayerFeature(100, 100, 0, false, true, -1);
    InventoryFeature inventory = new InventoryFeature(cp + 1, cp + 1, cp);
    NearbyNpc spot = new NearbyNpc("fishing spot", 101, 100, 0, 1);
    return new FishingState(player, inventory, List.of(spot));
  }

  private FishingModel model(String modelId, String recordingId, int schemaVersion) {
    List<TrainingExample> examples =
        List.of(new TrainingExample(stateAt(0), NET_FISHING_SPOT, 5000L));
    Map<String, Object> featureWeights = new LinkedHashMap<>();
    FishingDemonstrationPolicy.FEATURE_WEIGHTS.forEach(featureWeights::put);
    return new FishingModel(
        schemaVersion,
        modelId,
        FishingDemonstrationPolicy.KIND,
        "2026-08-11T05:20:00Z",
        "fishing_dataset_1",
        recordingId,
        "debug_train",
        5,
        List.of(NET_FISHING_SPOT),
        examples,
        featureWeights,
        FishingDemonstrationPolicy.MAX_ACCEPTED_POSITION_DISTANCE);
  }

  private void writeModel(FishingModel model) throws Exception {
    Files.createDirectories(modelsDir);
    objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(modelsDir.resolve(model.modelId() + ".json").toFile(), model);
  }

  private void writePassedRun(String modelId, String runId) throws Exception {
    Files.createDirectories(runsDir);
    RunReport report =
        new RunReport(
            "PASSED",
            runId,
            modelId,
            "recording_train",
            "recording_test",
            "RECORDED_DECISION_GATES",
            5,
            5,
            0,
            0,
            5,
            true,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(runsDir.resolve(runId + ".json").toFile(), report);
  }

  private void writeActivationFile(String modelId) throws Exception {
    Files.createDirectories(tempDir.resolve("fishing"));
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("schemaVersion", 1);
    state.put("modelId", modelId);
    state.put("activatedAt", "2026-08-11T05:30:00Z");
    objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(tempDir.resolve("fishing").resolve("active-model.json").toFile(), state);
  }

  private void assertDiskCacheConsistent(FishingActiveModelService service) throws Exception {
    String cached = service.getActiveModelId();
    Path file = tempDir.resolve("fishing").resolve("active-model.json");
    if (cached == null) {
      assertThat(Files.exists(file)).isFalse();
    } else {
      JsonNode node = objectMapper.readTree(file.toFile());
      assertThat(node.path("modelId").asText()).isEqualTo(cached);
    }
  }

  @Test
  @DisplayName("a replay-validated model activates and survives service recreation")
  void validatedModelActivatesAndSurvivesRestart() throws Exception {
    writeModel(model("fishing_abc", "recording_train", 1));
    writePassedRun("fishing_abc", "fishing_run_abc");

    FishingActiveModelService service = newService();
    FishingModelSummary summary = service.activate("fishing_abc");

    assertThat(summary.modelId()).isEqualTo("fishing_abc");
    assertThat(summary.replayValidated()).isTrue();
    assertThat(summary.active()).isTrue();
    assertThat(service.getActiveModelId()).isEqualTo("fishing_abc");
    assertThat(service.getActiveModel()).isNotNull();

    FishingActiveModelService recreated = newService();
    recreated.restoreActivation();
    assertThat(recreated.getActiveModelId()).isEqualTo("fishing_abc");
    assertThat(recreated.getActiveModel()).isNotNull();
    assertThat(recreated.getActivationLoadError()).isNull();
  }

  @Test
  @DisplayName("an unvalidated model is rejected")
  void unvalidatedModelRejected() throws Exception {
    writeModel(model("fishing_unvalidated", "recording_train", 1));

    FishingActiveModelService service = newService();
    assertThatThrownBy(() -> service.activate("fishing_unvalidated"))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> {
              PrototypeApiException ex = (PrototypeApiException) e;
              assertThat(ex.getCode()).isEqualTo("MODEL_NOT_REPLAY_VALIDATED");
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
    assertThat(service.getActiveModelId()).isNull();
  }

  @Test
  @DisplayName("a missing, malformed, or unsupported model is rejected safely")
  void missingMalformedOrUnsupportedModelRejected() throws Exception {
    FishingActiveModelService service = newService();

    assertThatThrownBy(() -> service.activate("fishing_missing"))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> assertThat(((PrototypeApiException) e).getCode()).isEqualTo("MODEL_NOT_FOUND"));

    Files.createDirectories(modelsDir);
    Files.writeString(modelsDir.resolve("fishing_malformed.json"), "not json {");
    assertThatThrownBy(() -> service.activate("fishing_malformed"))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> assertThat(((PrototypeApiException) e).getCode()).isEqualTo("MODEL_LOAD_FAILED"));

    writeModel(model("fishing_bad_schema", "recording_train", 99));
    writePassedRun("fishing_bad_schema", "fishing_run_bad_schema");
    assertThatThrownBy(() -> service.activate("fishing_bad_schema"))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e ->
                assertThat(((PrototypeApiException) e).getCode())
                    .isEqualTo("MODEL_SCHEMA_UNSUPPORTED"));
  }

  @Test
  @DisplayName("a corrupt activation file starts safely with no active model")
  void corruptActivationStartsSafely() throws Exception {
    Files.createDirectories(tempDir.resolve("fishing"));
    Files.writeString(tempDir.resolve("fishing").resolve("active-model.json"), "corrupt {");

    FishingActiveModelService service = newService();
    service.restoreActivation();
    assertThat(service.getActiveModelId()).isNull();
    assertThat(service.getActiveModel()).isNull();
    assertThat(service.getActivationLoadError()).isEqualTo("ACTIVATION_STATE_CORRUPT");
    assertThat(service.getActivationState()).isEqualTo("ACTIVATION_STATE_CORRUPT");
  }

  @Test
  @DisplayName("a missing activation file is a normal inactive state, not corruption")
  void missingActivationIsDistinguishedFromCorruption() {
    FishingActiveModelService service = newService();
    service.restoreActivation();
    assertThat(service.getActiveModelId()).isNull();
    assertThat(service.getActiveModel()).isNull();
    assertThat(service.getActivationLoadError()).isNull();
    assertThat(service.getActivationState()).isEqualTo("NO_ACTIVE_MODEL");
  }

  @Test
  @DisplayName("an activation referencing a model that lost replay validation restores safely")
  void activationWithoutPassedRunRestoresSafely() throws Exception {
    writeModel(model("fishing_no_run", "recording_train", 1));
    Files.createDirectories(tempDir.resolve("fishing"));
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("schemaVersion", 1);
    state.put("modelId", "fishing_no_run");
    state.put("activatedAt", "2026-08-11T05:30:00Z");
    objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(tempDir.resolve("fishing").resolve("active-model.json").toFile(), state);

    FishingActiveModelService service = newService();
    service.restoreActivation();
    assertThat(service.getActiveModelId()).isNull();
    assertThat(service.getActivationLoadError()).isEqualTo("MODEL_NOT_REPLAY_VALIDATED");
    assertThat(service.getActivationState()).isEqualTo("MODEL_NOT_REPLAY_VALIDATED");
  }

  @Test
  @DisplayName("a failed activation retains the previous active model")
  void failedActivationRetainsPreviousModel() throws Exception {
    writeModel(model("fishing_abc", "recording_train", 1));
    writePassedRun("fishing_abc", "fishing_run_abc");
    writeModel(model("fishing_new", "recording_train", 1));
    writePassedRun("fishing_new", "fishing_run_new");

    FishingActiveModelService service = newService();
    service.activate("fishing_abc");
    assertThat(service.getActiveModelId()).isEqualTo("fishing_abc");

    // Make activation of the new model fail at the persistence boundary: the target
    // active-model.json is a non-empty directory, so the atomic replace cannot succeed.
    Path activeFile = tempDir.resolve("fishing").resolve("active-model.json");
    Files.deleteIfExists(activeFile);
    Files.createDirectories(activeFile);
    Files.writeString(activeFile.resolve("blocker"), "blocker");

    assertThatThrownBy(() -> service.activate("fishing_new"))
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> {
              PrototypeApiException ex = (PrototypeApiException) e;
              assertThat(ex.getCode()).isEqualTo("ACTIVATION_WRITE_FAILED");
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            });

    // The previous model is retained in memory with no in-memory/disk mismatch.
    assertThat(service.getActiveModelId()).isEqualTo("fishing_abc");
    assertThat(service.getActiveModel()).isNotNull();
    assertThat(service.getActiveModel().modelId()).isEqualTo("fishing_abc");
  }

  @Test
  @DisplayName("a failed deactivation retains the current model and returns an error")
  void failedDeactivationRetainsCurrentModel() throws Exception {
    writeModel(model("fishing_abc", "recording_train", 1));
    writePassedRun("fishing_abc", "fishing_run_abc");

    FishingActiveModelService service = newService();
    service.activate("fishing_abc");
    assertThat(service.getActiveModelId()).isEqualTo("fishing_abc");

    // Replace the activation file with a non-empty directory so deletion fails.
    Files.delete(tempDir.resolve("fishing").resolve("active-model.json"));
    Path activeFile = tempDir.resolve("fishing").resolve("active-model.json");
    Files.createDirectories(activeFile);
    Files.writeString(activeFile.resolve("blocker"), "blocker");

    assertThatThrownBy(() -> service.deactivate())
        .isInstanceOf(PrototypeApiException.class)
        .satisfies(
            e -> {
              PrototypeApiException ex = (PrototypeApiException) e;
              assertThat(ex.getCode()).isEqualTo("DEACTIVATION_WRITE_FAILED");
              assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            });

    // The cached model is retained; success is never reported.
    assertThat(service.getActiveModelId()).isEqualTo("fishing_abc");
    assertThat(service.getActiveModel()).isNotNull();
    assertThat(service.getActiveModel().modelId()).isEqualTo("fishing_abc");
  }

  @Test
  @DisplayName("an activation referencing a deleted model restores safely inactive")
  void activationReferencingDeletedModelStartsSafely() throws Exception {
    Files.createDirectories(tempDir.resolve("fishing"));
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("schemaVersion", 1);
    state.put("modelId", "fishing_deleted");
    state.put("activatedAt", "2026-08-11T05:30:00Z");
    objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(tempDir.resolve("fishing").resolve("active-model.json").toFile(), state);

    FishingActiveModelService service = newService();
    service.restoreActivation();
    assertThat(service.getActiveModelId()).isNull();
    assertThat(service.getActivationLoadError()).isEqualTo("MODEL_NOT_FOUND");
  }

  @Test
  @DisplayName("deactivation removes the active model and its persisted state")
  void deactivationRemovesActiveModel() throws Exception {
    writeModel(model("fishing_abc", "recording_train", 1));
    writePassedRun("fishing_abc", "fishing_run_abc");

    FishingActiveModelService service = newService();
    service.activate("fishing_abc");
    assertThat(service.getActiveModelId()).isEqualTo("fishing_abc");

    Map<String, Object> result = service.deactivate();
    assertThat(result).containsEntry("status", "DEACTIVATED");
    assertThat(result).containsEntry("activeModelId", null);
    assertThat(service.getActiveModelId()).isNull();
    assertThat(Files.exists(tempDir.resolve("fishing").resolve("active-model.json"))).isFalse();

    FishingActiveModelService recreated = newService();
    recreated.restoreActivation();
    assertThat(recreated.getActiveModelId()).isNull();
  }

  @Test
  @DisplayName("the newest model is never auto-activated")
  void newestModelNotAutoActivated() throws Exception {
    writeModel(model("fishing_old", "recording_train", 1));
    writeModel(model("fishing_new", "recording_train", 1));

    FishingActiveModelService service = newService();
    service.restoreActivation();
    assertThat(service.getActiveModelId()).isNull();
  }

  @Test
  @DisplayName("listing models reports replay validation and active state")
  void listModelsReportsValidationAndActiveState() throws Exception {
    writeModel(model("fishing_abc", "recording_train", 1));
    writeModel(model("fishing_def", "recording_train", 1));
    writePassedRun("fishing_abc", "fishing_run_abc");

    FishingActiveModelService service = newService();
    service.activate("fishing_abc");

    Map<String, Object> response = service.listModelsResponse();
    assertThat(response).containsEntry("activeModelId", "fishing_abc");
    List<?> models = (List<?>) response.get("models");
    assertThat(models).hasSize(2);
    FishingModelSummary abc =
        ((List<FishingModelSummary>) models)
            .stream().filter(m -> m.modelId().equals("fishing_abc")).findFirst().orElseThrow();
    assertThat(abc.replayValidated()).isTrue();
    assertThat(abc.active()).isTrue();
    FishingModelSummary def =
        ((List<FishingModelSummary>) models)
            .stream().filter(m -> m.modelId().equals("fishing_def")).findFirst().orElseThrow();
    assertThat(def.replayValidated()).isFalse();
    assertThat(def.active()).isFalse();
  }

  @Test
  @DisplayName(
      "concurrent activation and deactivation never leaves disk and cache on different models")
  void concurrentActivationDeactivationKeepsDiskAndCacheConsistent() throws Exception {
    writeModel(model("fishing_a", "recording_a", 1));
    writePassedRun("fishing_a", "fishing_run_a");
    writeModel(model("fishing_b", "recording_b", 1));
    writePassedRun("fishing_b", "fishing_run_b");

    FishingActiveModelService service = newService();
    int workers = 4;
    ExecutorService executor = Executors.newFixedThreadPool(workers + 1);
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean inconsistent = new AtomicBoolean(false);
    AtomicReference<String> unexpectedError = new AtomicReference<>();
    List<Future<?>> futures = new ArrayList<>();

    // Continuous checker: every getActiveSnapshot() must be internally consistent (model present
    // iff
    // modelId present, and model.modelId() always equals modelId()).
    Future<?> checker =
        executor.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              while (!Thread.currentThread().isInterrupted()) {
                FishingActiveModelService.ActiveModelInfo info = service.getActiveSnapshot();
                if ((info.model() == null) != (info.modelId() == null)) {
                  inconsistent.set(true);
                }
                if (info.model() != null && !info.model().modelId().equals(info.modelId())) {
                  inconsistent.set(true);
                }
              }
              return null;
            });

    for (int t = 0; t < workers; t++) {
      String modelId = (t % 2 == 0) ? "fishing_a" : "fishing_b";
      futures.add(
          executor.submit(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                try {
                  for (int i = 0; i < 150; i++) {
                    if (i % 3 == 0) {
                      service.activate(modelId);
                    } else if (i % 3 == 1) {
                      service.deactivate();
                    } else {
                      service.activate(modelId);
                    }
                  }
                } catch (RuntimeException e) {
                  unexpectedError.compareAndSet(null, e.toString());
                }
                return null;
              }));
    }

    start.countDown();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdownNow();
    checker.get(5, TimeUnit.SECONDS);

    assertThat(unexpectedError.get()).isNull();
    assertThat(inconsistent.get()).isFalse();

    // After quiescence the persisted file and the cached model must agree.
    String cached = service.getActiveModelId();
    Path file = tempDir.resolve("fishing").resolve("active-model.json");
    if (cached == null) {
      assertThat(Files.exists(file)).isFalse();
    } else {
      JsonNode node = objectMapper.readTree(file.toFile());
      assertThat(node.path("modelId").asText()).isEqualTo(cached);
    }
  }

  @Test
  @DisplayName("two simultaneous deactivations are safe and leave no active model")
  void simultaneousDeactivationsAreSafe() throws Exception {
    writeModel(model("fishing_abc", "recording_train", 1));
    writePassedRun("fishing_abc", "fishing_run_abc");

    FishingActiveModelService service = newService();
    service.activate("fishing_abc");

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                service.deactivate();
                return null;
              }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertThat(service.getActiveModelId()).isNull();
    assertThat(Files.exists(tempDir.resolve("fishing").resolve("active-model.json"))).isFalse();
    FishingActiveModelService.ActiveModelInfo info = service.getActiveSnapshot();
    assertThat(info.model()).isNull();
    assertThat(info.modelId()).isNull();
  }

  @Test
  @DisplayName("activate versus deactivate on the same model leaves disk and cache consistent")
  void activateVsDeactivateRaceIsConsistent() throws Exception {
    writeModel(model("fishing_abc", "recording_train", 1));
    writePassedRun("fishing_abc", "fishing_run_abc");

    FishingActiveModelService service = newService();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    futures.add(
        executor.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              for (int i = 0; i < 100; i++) {
                service.activate("fishing_abc");
              }
              return null;
            }));
    futures.add(
        executor.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              for (int i = 0; i < 100; i++) {
                service.deactivate();
              }
              return null;
            }));
    start.countDown();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();

    // Whatever the final state, the persisted file and the cache must describe the same model.
    assertDiskCacheConsistent(service);
  }

  @Test
  @DisplayName("restore after the active model's activation file is deleted clears the cache")
  void restoreAfterActivationFileDeletedClearsCache() throws Exception {
    writeModel(model("fishing_abc", "recording_train", 1));
    writePassedRun("fishing_abc", "fishing_run_abc");

    FishingActiveModelService service = newService();
    service.activate("fishing_abc");
    assertThat(service.getActiveModelId()).isEqualTo("fishing_abc");

    Files.delete(tempDir.resolve("fishing").resolve("active-model.json"));
    service.restoreActivation();

    assertThat(service.getActiveModelId()).isNull();
    assertThat(service.getActiveModel()).isNull();
    assertThat(service.getActivationState()).isEqualTo("NO_ACTIVE_MODEL");
    assertThat(service.getActivationLoadError()).isNull();
  }

  @Test
  @DisplayName("restore after the active model's activation file becomes corrupt clears the cache")
  void restoreAfterActivationFileCorruptClearsCache() throws Exception {
    writeModel(model("fishing_abc", "recording_train", 1));
    writePassedRun("fishing_abc", "fishing_run_abc");

    FishingActiveModelService service = newService();
    service.activate("fishing_abc");
    assertThat(service.getActiveModelId()).isEqualTo("fishing_abc");

    Files.writeString(tempDir.resolve("fishing").resolve("active-model.json"), "corrupt {");
    service.restoreActivation();

    assertThat(service.getActiveModelId()).isNull();
    assertThat(service.getActiveModel()).isNull();
    assertThat(service.getActivationState()).isEqualTo("ACTIVATION_STATE_CORRUPT");
    assertThat(service.getActivationLoadError()).isEqualTo("ACTIVATION_STATE_CORRUPT");
  }

  @Test
  @DisplayName("every restore failure path clears the previously cached model")
  void restoreFailurePathsClearCachedModel() throws Exception {
    writeModel(model("fishing_abc", "recording_train", 1));
    writePassedRun("fishing_abc", "fishing_run_abc");

    FishingActiveModelService service = newService();
    service.activate("fishing_abc");
    assertThat(service.getActiveModelId()).isEqualTo("fishing_abc");

    // Missing model file.
    writeActivationFile("fishing_missing");
    service.restoreActivation();
    assertThat(service.getActiveModelId()).isNull();
    assertThat(service.getActivationLoadError()).isEqualTo("MODEL_NOT_FOUND");

    // Unsupported schema.
    service.activate("fishing_abc");
    writeModel(model("fishing_bad_schema", "recording_train", 99));
    writePassedRun("fishing_bad_schema", "fishing_run_bad_schema");
    writeActivationFile("fishing_bad_schema");
    service.restoreActivation();
    assertThat(service.getActiveModelId()).isNull();
    assertThat(service.getActivationLoadError()).isEqualTo("MODEL_SCHEMA_UNSUPPORTED");

    // No persisted PASSED held-out run.
    service.activate("fishing_abc");
    writeModel(model("fishing_no_run", "recording_train", 1));
    writeActivationFile("fishing_no_run");
    service.restoreActivation();
    assertThat(service.getActiveModelId()).isNull();
    assertThat(service.getActivationLoadError()).isEqualTo("MODEL_NOT_REPLAY_VALIDATED");

    // A successful restore rebuilds the cache.
    writeActivationFile("fishing_abc");
    service.restoreActivation();
    assertThat(service.getActiveModelId()).isEqualTo("fishing_abc");
    assertThat(service.getActiveModel()).isNotNull();
    assertThat(service.getActivationLoadError()).isNull();
  }

  @Test
  @DisplayName(
      "restore racing with activation and deactivation never leaves disk and cache on different models")
  void restoreRacingWithActivationAndDeactivationKeepsDiskAndCacheConsistent() throws Exception {
    writeModel(model("fishing_a", "recording_a", 1));
    writePassedRun("fishing_a", "fishing_run_a");
    writeModel(model("fishing_b", "recording_b", 1));
    writePassedRun("fishing_b", "fishing_run_b");

    FishingActiveModelService service = newService();
    ExecutorService executor = Executors.newFixedThreadPool(3);
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean inconsistent = new AtomicBoolean(false);
    AtomicReference<String> unexpectedError = new AtomicReference<>();
    List<Future<?>> futures = new ArrayList<>();

    futures.add(
        executor.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              for (int i = 0; i < 100; i++) {
                service.activate(i % 2 == 0 ? "fishing_a" : "fishing_b");
              }
              return null;
            }));
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
                  service.restoreActivation();
                  checkSnapshotConsistency(service, inconsistent);
                } catch (Exception e) {
                  inconsistent.set(true);
                }
              }
              return null;
            }));
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
                  service.deactivate();
                } catch (RuntimeException e) {
                  // A deactivation racing an activation may fail its write; the invariant checked
                  // below is that disk and cache never disagree, which deactivate never violates.
                  unexpectedError.compareAndSet(null, e.toString());
                }
              }
              return null;
            }));

    start.countDown();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();
    // The disk-vs-cache equivalence is only checkable at quiescence; during the race the snapshot
    // check above is atomic under the activation lock while the file read is not.
    assertThat(unexpectedError.get()).isNull();
    assertThat(inconsistent.get()).isFalse();
    assertDiskCacheConsistent(service);
  }

  /** Atomic internal-consistency check of the active-model snapshot (safe during a race). */
  private void checkSnapshotConsistency(
      FishingActiveModelService service, AtomicBoolean inconsistent) {
    FishingActiveModelService.ActiveModelInfo info = service.getActiveSnapshot();
    if ((info.model() == null) != (info.modelId() == null)) {
      inconsistent.set(true);
    }
    if (info.model() != null && !info.model().modelId().equals(info.modelId())) {
      inconsistent.set(true);
    }
  }

  /** Disk-vs-cache equivalence, valid only once all concurrent operations have quiesced. */
  private void checkDiskCacheConsistency(
      FishingActiveModelService service, AtomicBoolean inconsistent) {
    String cached = service.getActiveModelId();
    Path file = tempDir.resolve("fishing").resolve("active-model.json");
    try {
      if (cached == null) {
        if (Files.exists(file)) {
          inconsistent.set(true);
        }
      } else {
        if (!Files.exists(file)) {
          inconsistent.set(true);
          return;
        }
        JsonNode node = objectMapper.readTree(file.toFile());
        if (!cached.equals(node.path("modelId").asText())) {
          inconsistent.set(true);
        }
      }
    } catch (IOException e) {
      inconsistent.set(true);
    }
  }
}
