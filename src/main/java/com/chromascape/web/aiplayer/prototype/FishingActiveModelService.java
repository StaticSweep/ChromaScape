package com.chromascape.web.aiplayer.prototype;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Manages the single explicitly activated fishing model used by live shadow inference.
 *
 * <p>Activation is transactional: the persisted state is written with a same-directory temporary
 * file plus an atomic move before the cached model and ID are updated together. A write failure
 * retains the previous active model and never leaves an in-memory/disk mismatch. Activation is
 * restored on restart; a missing activation file is a normal inactive state, not corruption.
 */
@Service
public class FishingActiveModelService {

  private static final Logger log = LoggerFactory.getLogger(FishingActiveModelService.class);
  private static final Pattern SAFE_MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
  private static final int ACTIVATION_SCHEMA_VERSION = 1;

  private final FishingDatasetBuilderService modelStore;
  private final ObjectMapper objectMapper;

  @Value("${ai-player.dataset.output-dir:.chromascape/datasets}")
  private String outputDir;

  private final Object activationLock = new Object();
  private volatile ActiveModelSnapshot active;
  private volatile String activationLoadError;
  private volatile String activationState;

  /** Immutable snapshot of the cached active model and its cached replay-validation result. */
  private static final class ActiveModelSnapshot {
    private final FishingModel model;
    private final String modelId;
    private final boolean replayValidated;

    private ActiveModelSnapshot(FishingModel model, String modelId, boolean replayValidated) {
      this.model = model;
      this.modelId = modelId;
      this.replayValidated = replayValidated;
    }
  }

  /**
   * Internally consistent view of the active-model state captured under the activation lock. {@code
   * model}, {@code modelId}, and {@code summary} always describe the same cached snapshot, so
   * status reporting can never present a contradictory active model.
   */
  public record ActiveModelInfo(
      FishingModel model,
      String modelId,
      FishingModelSummary summary,
      String activationState,
      String activationLoadError) {}

  public FishingActiveModelService(
      FishingDatasetBuilderService modelStore, ObjectMapper objectMapper) {
    this.modelStore = modelStore;
    this.objectMapper = objectMapper;
  }

  /**
   * Restores the persisted activation on startup; failure to restore starts safely inactive. A
   * missing activation file is a normal inactive state, not corruption.
   *
   * <p>The entire restore (path resolution, file existence check, read, schema parsing, model
   * loading, replay validation, active snapshot update, and state/error update) runs inside the
   * activation lock shared with {@link #activate} and {@link #deactivate}, so no restore/activate/
   * deactivate interleaving can produce a disk/cache disagreement. Every restore attempt starts by
   * clearing the cached model, so each terminal result is explicit.
   */
  @PostConstruct
  public void restoreActivation() {
    synchronized (activationLock) {
      active = null;
      Path file = activationFile();
      if (!Files.exists(file)) {
        activationState = "NO_ACTIVE_MODEL";
        activationLoadError = null;
        return;
      }
      ActivationState state = readActivationState();
      if (state == null || state.modelId() == null || state.modelId().isBlank()) {
        activationState = "ACTIVATION_STATE_CORRUPT";
        activationLoadError = "ACTIVATION_STATE_CORRUPT";
        return;
      }
      try {
        FishingModel model = validatedModel(state.modelId());
        boolean replayValidated = hasPassedRun(state.modelId());
        if (!replayValidated) {
          throw new PrototypeApiException(
              HttpStatus.BAD_REQUEST,
              "MODEL_NOT_REPLAY_VALIDATED",
              "Model has no persisted PASSED held-out run and cannot be restored",
              Map.of("modelId", state.modelId()));
        }
        active = new ActiveModelSnapshot(model, state.modelId(), replayValidated);
        activationState = null;
        activationLoadError = null;
        log.info("Restored active fishing model: {}", state.modelId());
      } catch (PrototypeApiException e) {
        activationLoadError = e.getCode();
        activationState = e.getCode();
        log.warn(
            "Could not restore active model {}: {} ({})",
            state.modelId(),
            e.getCode(),
            e.getMessage());
      } catch (RuntimeException e) {
        activationLoadError = "ACTIVATION_RESTORE_FAILED";
        activationState = "ACTIVATION_RESTORE_FAILED";
        log.error("Failed to restore active model {}", state.modelId(), e);
      }
    }
  }

  /** Lists all persisted fishing model summaries plus the currently active model id. */
  public Map<String, Object> listModelsResponse() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("activeModelId", getActiveModelId());
    response.put("models", listModels());
    return response;
  }

  /**
   * Explicitly activates a model.
   *
   * @param modelId the model to activate
   * @return the activated model summary
   */
  public FishingModelSummary activate(String modelId) {
    if (modelId == null || modelId.isBlank()) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST, "MISSING_MODEL_ID", "modelId is required");
    }
    if (!SAFE_MODEL_ID.matcher(modelId).matches()) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST,
          "INVALID_MODEL_ID",
          "modelId must contain only letters, numbers, '_' or '-'",
          Map.of("modelId", modelId));
    }
    // The complete activation operation (validation, persistence, cache update, state update) is
    // serialized under the activation lock so concurrent activations/deactivations can never leave
    // the persisted file and the cached model on different models.
    synchronized (activationLock) {
      FishingModel model = validatedModel(modelId);
      boolean replayValidated = hasPassedRun(modelId);
      if (!replayValidated) {
        throw new PrototypeApiException(
            HttpStatus.BAD_REQUEST,
            "MODEL_NOT_REPLAY_VALIDATED",
            "Model has no persisted PASSED held-out run and cannot be activated",
            Map.of("modelId", modelId));
      }
      writeActivationState(modelId);
      ActiveModelSnapshot snapshot = new ActiveModelSnapshot(model, modelId, replayValidated);
      active = snapshot;
      activationLoadError = null;
      activationState = null;
      log.info("Activated fishing model: {}", modelId);
      return summary(snapshot);
    }
  }

  /**
   * Deactivates the current model and clears the persisted activation state.
   *
   * @throws PrototypeApiException {@code DEACTIVATION_WRITE_FAILED} when the persisted state cannot
   *     be deleted; the cached model is retained and success is never reported.
   */
  public Map<String, Object> deactivate() {
    synchronized (activationLock) {
      try {
        Files.deleteIfExists(activationFile());
      } catch (IOException e) {
        log.warn("Failed to delete activation state: {}", e.getMessage());
        throw new PrototypeApiException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "DEACTIVATION_WRITE_FAILED",
            "Failed to delete the persisted active model",
            Map.of());
      }
      active = null;
      activationLoadError = null;
      activationState = null;
      log.info("Deactivated fishing model");
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("status", "DEACTIVATED");
      response.put("activeModelId", null);
      return response;
    }
  }

  /** Returns the cached active model for live inference (null when none active). */
  public FishingModel getActiveModel() {
    ActiveModelSnapshot snapshot = active;
    return snapshot != null ? snapshot.model : null;
  }

  /** Returns the active model id (null when none active). */
  public String getActiveModelId() {
    ActiveModelSnapshot snapshot = active;
    return snapshot != null ? snapshot.modelId : null;
  }

  /** Returns the active model summary for status reporting (null when none active). */
  public FishingModelSummary getActiveModelSummary() {
    ActiveModelSnapshot snapshot = active;
    if (snapshot == null) {
      return null;
    }
    return summary(snapshot);
  }

  /**
   * Returns an internally consistent snapshot of the active model, its summary, and the activation
   * state/error, all captured under the activation lock. Status reporting must build from this
   * single snapshot so an activation change between reads can never produce contradictory fields.
   */
  public ActiveModelInfo getActiveSnapshot() {
    synchronized (activationLock) {
      ActiveModelSnapshot snapshot = active;
      return new ActiveModelInfo(
          snapshot != null ? snapshot.model : null,
          snapshot != null ? snapshot.modelId : null,
          snapshot != null ? summary(snapshot) : null,
          activationState,
          activationLoadError);
    }
  }

  /** Returns the reason the restored activation was rejected (null when active or unset). */
  public String getActivationLoadError() {
    return activationLoadError;
  }

  /** Returns the current activation state (null when active, otherwise one of the state codes). */
  public String getActivationState() {
    return activationState;
  }

  private List<FishingModelSummary> listModels() {
    List<FishingModelSummary> summaries = new ArrayList<>();
    Path modelsDir = modelsDir();
    if (!Files.isDirectory(modelsDir)) {
      return summaries;
    }
    try (Stream<Path> files = Files.list(modelsDir)) {
      List<Path> artifacts =
          files.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
      for (Path artifact : artifacts) {
        String modelId = artifact.getFileName().toString().replace(".json", "");
        try {
          FishingModel model = modelStore.loadModel(modelId);
          summaries.add(summary(model, hasPassedRun(modelId)));
        } catch (PrototypeApiException e) {
          log.warn("Skipping unreadable model {}: {}", modelId, e.getCode());
        }
      }
    } catch (IOException e) {
      log.warn("Failed to list fishing models: {}", e.getMessage());
    }
    summaries.sort(
        (a, b) -> {
          String aTime = a.createdAt() != null ? a.createdAt() : "";
          String bTime = b.createdAt() != null ? b.createdAt() : "";
          int byTime = bTime.compareTo(aTime);
          return byTime != 0 ? byTime : a.modelId().compareTo(b.modelId());
        });
    return summaries;
  }

  private FishingModelSummary summary(ActiveModelSnapshot snapshot) {
    return summary(snapshot.model, snapshot.replayValidated);
  }

  private FishingModelSummary summary(FishingModel model, boolean replayValidated) {
    return new FishingModelSummary(
        model.modelId(),
        model.createdAt(),
        model.trainingRecordingId(),
        model.examples() != null ? model.examples().size() : 0,
        replayValidated,
        model.modelId().equals(getActiveModelId()));
  }

  private FishingModel validatedModel(String modelId) {
    FishingModel model = modelStore.loadModel(modelId);
    if (model.schemaVersion() != FishingDemonstrationPolicy.SCHEMA_VERSION
        || !FishingDemonstrationPolicy.KIND.equals(model.kind())
        || model.requiredCatchCount() != FishingPreflightService.REQUIRED_CATCH_COUNT) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST,
          "MODEL_SCHEMA_UNSUPPORTED",
          "Model is not a supported fishing demonstration artifact",
          Map.of(
              "modelId",
              modelId,
              "schemaVersion",
              model.schemaVersion(),
              "kind",
              model.kind() == null ? "" : model.kind(),
              "requiredCatchCount",
              model.requiredCatchCount()));
    }
    return model;
  }

  private boolean hasPassedRun(String modelId) {
    Path runsDir = Paths.get(outputDir, "fishing", "runs");
    if (!Files.isDirectory(runsDir)) {
      return false;
    }
    try (Stream<Path> files = Files.list(runsDir)) {
      List<Path> reports =
          files.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
      for (Path report : reports) {
        try {
          RunReport run = objectMapper.readValue(report.toFile(), RunReport.class);
          if ("PASSED".equals(run.status()) && modelId.equals(run.modelId())) {
            return true;
          }
        } catch (IOException e) {
          log.debug("Skipping unreadable run report {}", report.getFileName());
        }
      }
    } catch (IOException e) {
      log.warn("Failed to scan fishing runs: {}", e.getMessage());
    }
    return false;
  }

  private void writeActivationState(String modelId) {
    Path file = activationFile();
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("schemaVersion", ACTIVATION_SCHEMA_VERSION);
    state.put("modelId", modelId);
    state.put("activatedAt", Instant.now().toString());
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        log.warn(
            "ATOMIC_MOVE not supported for activation state; falling back to REPLACE_EXISTING");
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      log.error("Failed to persist activation state for {}", modelId, e);
      throw new PrototypeApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "ACTIVATION_WRITE_FAILED",
          "Failed to persist the active model",
          Map.of("modelId", modelId));
    }
  }

  private ActivationState readActivationState() {
    Path file = activationFile();
    if (!Files.exists(file)) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(file.toFile());
      int schemaVersion = node.path("schemaVersion").asInt(-1);
      String modelId = node.path("modelId").isTextual() ? node.path("modelId").asText() : null;
      if (schemaVersion != ACTIVATION_SCHEMA_VERSION || modelId == null || modelId.isBlank()) {
        return null;
      }
      return new ActivationState(modelId);
    } catch (IOException e) {
      log.warn("Failed to read activation state: {}", e.getMessage());
      return null;
    }
  }

  private Path activationFile() {
    return Paths.get(outputDir, "fishing", "active-model.json");
  }

  private Path modelsDir() {
    return Paths.get(outputDir, "fishing", "models");
  }

  private record ActivationState(String modelId) {}
}
