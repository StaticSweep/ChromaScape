package com.chromascape.web.aiplayer.prototype;

import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.AlignedAction;
import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.RecordingContext;
import com.chromascape.web.aiplayer.prototype.PreflightReport.PreflightIssue;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Builds the persisted fishing dataset and deterministic model artifact from a single validated
 * recording.
 *
 * <p>Training first runs the same validation as preflight and produces no partial artifacts when
 * validation fails.
 */
@Service
public class FishingDatasetBuilderService {

  private static final Logger log = LoggerFactory.getLogger(FishingDatasetBuilderService.class);
  private static final Pattern SAFE_MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");

  private final FishingPreflightService preflightService;
  private final FishingRecordingLoader loader;
  private final ObjectMapper objectMapper;

  @Value("${ai-player.dataset.output-dir:.chromascape/datasets}")
  private String outputDir;

  @Value("${features.ai-player.enabled:false}")
  private boolean aiPlayerEnabled;

  @Value("${features.ai-player.shadow-mode:false}")
  private boolean shadowMode;

  @Value("${features.ai-player.control-enabled:false}")
  private boolean controlEnabled;

  public FishingDatasetBuilderService(
      FishingPreflightService preflightService,
      FishingRecordingLoader loader,
      ObjectMapper objectMapper) {
    this.preflightService = preflightService;
    this.loader = loader;
    this.objectMapper = objectMapper;
  }

  /**
   * Trains a demonstration baseline model from a recording.
   *
   * @param recordingId the training recording
   * @param requiredCatchCount the required catch count (prototype one only accepts 5)
   * @return the training result with artifact paths
   */
  public TrainingResult train(String recordingId, Integer requiredCatchCount) {
    ensureEnabled();
    int required =
        requiredCatchCount != null
            ? requiredCatchCount
            : FishingPreflightService.REQUIRED_CATCH_COUNT;
    if (required != FishingPreflightService.REQUIRED_CATCH_COUNT) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST,
          "UNSUPPORTED_CATCH_COUNT",
          "Prototype one accepts only requiredCatchCount=5");
    }

    DebugSession session = preflightService.resolveRecording(recordingId);
    PreflightReport report = preflightService.preflight(recordingId);
    if (!report.isReady()) {
      PreflightIssue firstIssue = report.issues().isEmpty() ? null : report.issues().get(0);
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST,
          firstIssue != null ? firstIssue.code() : "RECORDING_UNSUITABLE",
          firstIssue != null ? firstIssue.message() : "Recording is unsuitable for training",
          Map.of("recordingId", recordingId, "issues", report.issues()));
    }

    RecordingContext ctx = loader.load(session);
    String datasetId = "fishing_dataset_" + randomHex8();
    String modelId = "fishing_" + randomHex8();

    List<DatasetExample> examples = buildExamples(ctx, recordingId);
    List<SemanticAction> vocabulary = buildVocabulary(examples);
    Path datasetPath = writeDataset(datasetId, ctx, examples);
    Path manifestPath = writeManifest(datasetId, ctx, examples, vocabulary);
    Path modelPath = writeModel(modelId, datasetId, ctx, vocabulary, examples, required);

    log.info(
        "Fishing model trained: modelId={}, datasetId={}, examples={}, recording={}",
        modelId,
        datasetId,
        examples.size(),
        recordingId);

    return new TrainingResult(
        "TRAINED",
        modelId,
        datasetId,
        recordingId,
        session.getSessionId(),
        examples.size(),
        vocabulary,
        datasetPath.toString(),
        manifestPath.toString(),
        modelPath.toString());
  }

  /** Returns the artifact output base directory. */
  public String getArtifactDir() {
    return outputDir;
  }

  public boolean isAiPlayerEnabled() {
    return aiPlayerEnabled;
  }

  private List<DatasetExample> buildExamples(RecordingContext ctx, String recordingId) {
    List<DatasetExample> examples = new ArrayList<>();
    for (AlignedAction aligned : ctx.alignedActions()) {
      FishingState state =
          FishingStateExtractor.extract(
              aligned.snapshot(), ctx.inventoryTotals(), ctx.initialInventoryTotal());
      SemanticAction action = FishingActionNormalizer.classify(aligned.action()).semanticAction();
      if (action == null) {
        continue;
      }
      examples.add(
          new DatasetExample(
              1,
              recordingId,
              ctx.session().getSessionId(),
              aligned.action().sourceTick(),
              aligned.snapshotTick(),
              state,
              action));
    }
    return examples;
  }

  private List<SemanticAction> buildVocabulary(List<DatasetExample> examples) {
    List<SemanticAction> vocabulary = new ArrayList<>();
    for (DatasetExample example : examples) {
      if (vocabulary.stream().noneMatch(v -> v.matches(example.action()))) {
        vocabulary.add(example.action());
      }
    }
    return vocabulary;
  }

  private Path writeDataset(String datasetId, RecordingContext ctx, List<DatasetExample> examples) {
    Path outputPath = datasetsDir().resolve(datasetId + ".jsonl");
    try {
      Files.createDirectories(outputPath.getParent());
      try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
        for (DatasetExample example : examples) {
          writer.write(objectMapper.writeValueAsString(example));
          writer.newLine();
        }
      }
    } catch (IOException e) {
      throw new PrototypeApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "ARTIFACT_IO_FAILED",
          "Failed to write dataset artifact",
          Map.of("path", outputPath.toString()));
    }
    return outputPath;
  }

  private Path writeManifest(
      String datasetId,
      RecordingContext ctx,
      List<DatasetExample> examples,
      List<SemanticAction> vocabulary) {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("datasetId", datasetId);
    manifest.put("schemaVersion", 1);
    manifest.put("generatedAt", Instant.now().toString());
    manifest.put("recordingId", ctx.session().getRecordingId());
    manifest.put("sessionId", ctx.session().getSessionId());
    manifest.put("snapshotCount", ctx.snapshots().size());
    manifest.put("actionCount", ctx.playerActions().size());
    manifest.put("acceptedActionCount", examples.size());
    manifest.put(
        "rejectedActionCount", ctx.unsupportedActions().size() + ctx.unalignedActionCount());
    manifest.put("alignmentFallbackCount", ctx.alignmentFallbackCount());
    manifest.put("catchCount", ctx.catchDelta());
    manifest.put(
        "fishingXpDelta",
        ctx.firstFishingXp() >= 0 && ctx.lastFishingXp() >= 0
            ? ctx.lastFishingXp() - ctx.firstFishingXp()
            : 0);
    manifest.put("actionVocabulary", vocabulary);
    manifest.put(
        "featureFlags",
        Map.of(
            "ai_player_enabled", aiPlayerEnabled,
            "shadow_mode", shadowMode,
            "control_enabled", controlEnabled,
            "control_allowed", aiPlayerEnabled && controlEnabled));

    Path manifestPath = datasetsDir().resolve(datasetId + ".manifest.json");
    try {
      Files.createDirectories(manifestPath.getParent());
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
    } catch (IOException e) {
      throw new PrototypeApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "ARTIFACT_IO_FAILED",
          "Failed to write dataset manifest",
          Map.of("path", manifestPath.toString()));
    }
    return manifestPath;
  }

  private Path writeModel(
      String modelId,
      String datasetId,
      RecordingContext ctx,
      List<SemanticAction> vocabulary,
      List<DatasetExample> examples,
      int requiredCatchCount) {
    Map<String, Object> model = new LinkedHashMap<>();
    model.put("schemaVersion", FishingDemonstrationPolicy.SCHEMA_VERSION);
    model.put("modelId", modelId);
    model.put("kind", FishingDemonstrationPolicy.KIND);
    model.put("createdAt", Instant.now().toString());
    model.put("trainingDatasetId", datasetId);
    model.put("trainingRecordingId", ctx.session().getRecordingId());
    model.put("trainingSessionId", ctx.session().getSessionId());
    model.put("requiredCatchCount", requiredCatchCount);
    model.put("actionVocabulary", vocabulary);
    List<TrainingExample> trainingExamples = new ArrayList<>();
    for (DatasetExample example : examples) {
      trainingExamples.add(
          new TrainingExample(example.state(), example.action(), example.sourceTick()));
    }
    model.put("examples", trainingExamples);
    model.put("featureWeights", FishingDemonstrationPolicy.FEATURE_WEIGHTS);
    model.put(
        "maxAcceptedPositionDistance", FishingDemonstrationPolicy.MAX_ACCEPTED_POSITION_DISTANCE);

    Path modelPath = modelsDir().resolve(modelId + ".json");
    try {
      Files.createDirectories(modelPath.getParent());
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(modelPath.toFile(), model);
    } catch (IOException e) {
      throw new PrototypeApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "ARTIFACT_IO_FAILED",
          "Failed to write model artifact",
          Map.of("path", modelPath.toString()));
    }
    return modelPath;
  }

  /** Loads a persisted model artifact. */
  public FishingModel loadModel(String modelId) {
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
    Path modelPath = modelsDir().resolve(modelId + ".json");
    if (!Files.exists(modelPath)) {
      throw new PrototypeApiException(
          HttpStatus.NOT_FOUND,
          "MODEL_NOT_FOUND",
          "No model found for modelId " + modelId,
          Map.of("modelId", modelId));
    }
    try {
      return objectMapper.readValue(modelPath.toFile(), FishingModel.class);
    } catch (IOException e) {
      throw new PrototypeApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "MODEL_LOAD_FAILED",
          "Failed to read model artifact for modelId " + modelId,
          Map.of("modelId", modelId));
    }
  }

  private Path datasetsDir() {
    return Paths.get(outputDir, "fishing", "datasets");
  }

  private Path modelsDir() {
    return Paths.get(outputDir, "fishing", "models");
  }

  private void ensureEnabled() {
    if (!aiPlayerEnabled) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST, "AI_PLAYER_DISABLED", "AI player feature is disabled");
    }
  }

  private static String randomHex8() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
