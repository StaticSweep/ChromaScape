package com.chromascape.web.aiplayer.prototype;

import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.AlignedAction;
import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.RecordingContext;
import com.chromascape.web.aiplayer.prototype.PreflightReport.PreflightIssue;
import com.chromascape.web.artifact.ImmutableArtifactStore;
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

  @org.springframework.beans.factory.annotation.Autowired
  private ImmutableArtifactStore artifactStore;

  @Value("${artifacts.max-artifact-bytes:104857600}")
  private long maxArtifactBytes = 104857600;

  @Value("${artifacts.quota-bytes:1073741824}")
  private long artifactQuotaBytes = 1073741824;

  private static final String ARTIFACT_OWNER = "fishing-prototype";

  @org.springframework.beans.factory.annotation.Autowired
  private com.chromascape.web.recording.provenance.BehaviorRunContextService runContexts;

  @org.springframework.beans.factory.annotation.Autowired
  private com.chromascape.web.recording.provenance.CanonicalFrameStore canonicalFrames;

  @Value("${ai-player.dataset.output-dir:.chromascape/datasets}")
  private String outputDir;

  @Value("${artifacts.durability-mode:strict}")
  private String durabilityMode = "strict";

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
    if (runContexts == null)
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST,
          "RUN_CONTEXT_REQUIRED",
          "Canonical recording provenance is required");
    runContexts.requireTrainingContext(recordingId);
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
    Map<String, String> lineage = artifactLineage(ctx);
    var source = objectMapper.createObjectNode();
    source.put("recordingId", recordingId);
    source.put("sessionId", session.getSessionId());
    source.set("runContext", runContexts.requireTrainingContext(recordingId));
    source.set(
        "frameDigests",
        objectMapper.valueToTree(examples.stream().map(DatasetExample::frameDigest).toList()));
    byte[] sourceBytes;
    try {
      sourceBytes = objectMapper.writeValueAsBytes(source);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    String sourceId = "recording_" + ImmutableArtifactStore.checksum(sourceBytes).substring(7);
    store()
        .put(
            ARTIFACT_OWNER,
            sourceId,
            ImmutableArtifactStore.Kind.RECORDING,
            sourceBytes,
            "application/json",
            lineage,
            Map.of());
    Path datasetPath = writeDataset(datasetId, ctx, examples, sourceId);
    Path manifestPath = writeManifest(datasetId, ctx, examples, vocabulary, sourceId);
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
      var frame = canonicalFrames.atTick(recordingId, aligned.snapshotTick());
      FishingState state =
          com.chromascape.web.recording.provenance.CanonicalObservation.knownFishingState(
              com.chromascape.web.recording.provenance.CanonicalObservation.from(frame).payload());
      SemanticAction action = FishingActionNormalizer.classify(aligned.action()).semanticAction();
      if (action == null) {
        continue;
      }
      String canonicalId = action.type() + "_" + action.option().toUpperCase(java.util.Locale.ROOT);
      boolean matched = false;
      for (var event : frame.payload().path("semanticEvents")) {
        if (event.path("sourceTick").asLong(-1) == aligned.action().sourceTick()
            && event.path("actionId").asText().equals(canonicalId)
            && event.path("source").asText().equals("CLIENT_EVENT")
            && event.path("humanObserved").asBoolean()) matched = true;
      }
      if (!matched)
        throw new com.chromascape.web.recording.provenance.ProvenanceException(
            "CANONICAL_HUMAN_ACTION_REQUIRED");
      examples.add(
          new DatasetExample(
              1,
              recordingId,
              ctx.session().getSessionId(),
              aligned.action().sourceTick(),
              aligned.snapshotTick(),
              state,
              action,
              frame.digest(),
              com.chromascape.web.recording.provenance.BehaviorSemanticContract.digest()));
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

  private Path writeDataset(
      String datasetId, RecordingContext ctx, List<DatasetExample> examples, String sourceId) {
    Path outputPath = datasetsDir().resolve(datasetId + ".jsonl");
    try {
      var buffer = new java.io.StringWriter();
      try (BufferedWriter writer = new BufferedWriter(buffer)) {
        for (DatasetExample example : examples) {
          writer.write(objectMapper.writeValueAsString(example));
          writer.newLine();
        }
      }
      publishArtifact(
          datasetId,
          ImmutableArtifactStore.Kind.DATASET,
          buffer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
          "application/x-ndjson",
          ctx,
          Map.of("recording", sourceId),
          outputPath);
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
      List<SemanticAction> vocabulary,
      String sourceId) {
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("datasetId", datasetId);
    manifest.put("schemaVersion", 1);
    manifest.put("generatedAt", Instant.now().toString());
    manifest.put("recordingId", ctx.session().getRecordingId());
    manifest.put("runContext", runContexts.requireTrainingContext(ctx.session().getRecordingId()));
    manifest.put(
        "semanticContractDigest",
        com.chromascape.web.recording.provenance.BehaviorSemanticContract.digest());
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
            "control_requested", controlEnabled,
            "control_enabled", false,
            "control_allowed", false));

    Path manifestPath = datasetsDir().resolve(datasetId + ".manifest.json");
    try {
      publishArtifact(
          datasetId + "_manifest",
          ImmutableArtifactStore.Kind.DATASET_MANIFEST,
          objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest),
          "application/json",
          ctx,
          Map.of("recording", sourceId, "dataset", datasetId),
          manifestPath);
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
      publishArtifact(
          modelId,
          ImmutableArtifactStore.Kind.MODEL,
          objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(model),
          "application/json",
          ctx,
          Map.of("dataset", datasetId, "manifest", datasetId + "_manifest"),
          modelPath);
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
    try {
      var manifest = store().get(ARTIFACT_OWNER, modelId);
      if (manifest.kind() != ImmutableArtifactStore.Kind.MODEL)
        throw new PrototypeApiException(
            HttpStatus.CONFLICT, "MODEL_ARTIFACT_INVALID", "Artifact is not a model");
      return objectMapper.readValue(store().read(ARTIFACT_OWNER, modelId), FishingModel.class);
    } catch (org.springframework.web.server.ResponseStatusException e) {
      // A missing registry reference is the stable MODEL_NOT_FOUND contract for callers. Other
      // registry faults (corruption, quarantine, unavailable storage) keep their storage status.
      if (HttpStatus.NOT_FOUND.equals(e.getStatusCode()))
        throw new PrototypeApiException(
            HttpStatus.NOT_FOUND,
            "MODEL_NOT_FOUND",
            "No registered model artifact exists for modelId " + modelId,
            Map.of("modelId", modelId));
      throw e;
    } catch (IOException e) {
      throw new PrototypeApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "MODEL_LOAD_FAILED",
          "Failed to read model artifact for modelId " + modelId,
          Map.of("modelId", modelId));
    }
  }

  private ImmutableArtifactStore store() {
    return artifactStore != null
        ? artifactStore
        : new ImmutableArtifactStore(
            Path.of(outputDir).resolve("artifacts"),
            maxArtifactBytes,
            artifactQuotaBytes,
            objectMapper,
            ImmutableArtifactStore.DurabilityMode.valueOf(
                durabilityMode.trim().toUpperCase(java.util.Locale.ROOT)));
  }

  private Map<String, String> artifactLineage(RecordingContext ctx) {
    var context = runContexts.requireTrainingContext(ctx.session().getRecordingId());
    return Map.of(
        "recordingId",
        ctx.session().getRecordingId(),
        "sessionId",
        ctx.session().getSessionId(),
        "runContext",
        context.toString(),
        "semanticContract",
        com.chromascape.web.recording.provenance.BehaviorSemanticContract.digest());
  }

  private void publishArtifact(
      String id,
      ImmutableArtifactStore.Kind kind,
      byte[] content,
      String mediaType,
      RecordingContext ctx,
      Map<String, String> dependencies,
      Path compatibilityPath)
      throws IOException {
    var manifest =
        store()
            .put(ARTIFACT_OWNER, id, kind, content, mediaType, artifactLineage(ctx), dependencies);
    Files.createDirectories(compatibilityPath.getParent());
    try {
      Files.createLink(compatibilityPath, store().contentPath(manifest));
    } catch (java.nio.file.FileAlreadyExistsException exists) {
      // Never overwrite a mutable alias; verify it before accepting an identical retry.
      if (!Files.isRegularFile(compatibilityPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
          || Files.size(compatibilityPath) != content.length
          || !ImmutableArtifactStore.checksum(Files.readAllBytes(compatibilityPath))
              .equals(manifest.checksum()))
        throw new PrototypeApiException(
            HttpStatus.CONFLICT, "ARTIFACT_ALIAS_CONFLICT", "Artifact alias conflicts");
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
