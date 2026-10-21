package com.chromascape.web.aiplayer.prototype;

import com.chromascape.web.aiplayer.prototype.FishingDemonstrationPolicy.Prediction;
import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.AlignedAction;
import com.chromascape.web.aiplayer.prototype.FishingRecordingLoader.RecordingContext;
import com.chromascape.web.aiplayer.prototype.RunReport.RunDecision;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Runs the replay-gated held-out evaluation for the fishing prototype.
 *
 * <p>This is explicitly {@code RECORDED_DECISION_GATES} timing: the replay advances automatically
 * between human decision ticks and asks the model for exactly one semantic action at each gate.
 */
@Service
public class FishingReplayService {

  private static final Logger log = LoggerFactory.getLogger(FishingReplayService.class);
  private static final String DECISION_TIMING_MODE = "RECORDED_DECISION_GATES";

  private final FishingPreflightService preflightService;
  private final FishingRecordingLoader loader;
  private final FishingDatasetBuilderService modelStore;
  private final ObjectMapper objectMapper;

  @Value("${ai-player.dataset.output-dir:.chromascape/datasets}")
  private String outputDir;

  @Value("${features.ai-player.enabled:false}")
  private boolean aiPlayerEnabled;

  public FishingReplayService(
      FishingPreflightService preflightService,
      FishingRecordingLoader loader,
      FishingDatasetBuilderService modelStore,
      ObjectMapper objectMapper) {
    this.preflightService = preflightService;
    this.loader = loader;
    this.modelStore = modelStore;
    this.objectMapper = objectMapper;
  }

  /**
   * Evaluates a held-out recording against a trained model.
   *
   * @param modelId the trained model ID
   * @param recordingId the held-out test recording
   * @return the persisted run report
   */
  public RunReport run(String modelId, String recordingId) {
    ensureEnabled();
    modelStore.requireReplayRecording(recordingId, null);
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
    DebugSession session = preflightService.resolveRecording(recordingId);
    modelStore.requireReplayRecording(recordingId, session.getSessionId());

    if (session.getEndTick() == null) {
      throw new PrototypeApiException(
          HttpStatus.CONFLICT,
          PreflightReport.IssueCode.SESSION_NOT_FINALIZED,
          "Recording must be finalized before replay",
          Map.of("recordingId", recordingId));
    }
    if (model.trainingRecordingId() != null && model.trainingRecordingId().equals(recordingId)) {
      throw new PrototypeApiException(
          HttpStatus.CONFLICT,
          "SAME_TRAIN_TEST_RECORDING",
          "The training recording cannot be used as the test recording",
          Map.of(
              "modelId", modelId,
              "recordingId", recordingId,
              "trainingRecordingId", model.trainingRecordingId()));
    }

    PreflightReport preflight = preflightService.preflight(recordingId);
    if (!preflight.isReady()) {
      PreflightReport.PreflightIssue first =
          preflight.issues().isEmpty() ? null : preflight.issues().get(0);
      RunReport failed =
          new RunReport(
              "FAILED",
              newRunId(),
              modelId,
              model.trainingRecordingId(),
              recordingId,
              DECISION_TIMING_MODE,
              0,
              0,
              0,
              0,
              preflight.observedCatchDelta(),
              preflight.fishingXpIncreased(),
              List.of(),
              first != null ? first.code() : "RECORDING_UNSUITABLE",
              null,
              null,
              null,
              preflight.issues(),
              null);
      return persist(failed);
    }

    RecordingContext ctx = loader.load(session);
    List<RunDecision> decisions = new ArrayList<>();
    int expectedCount = ctx.alignedActions().size();
    int index = 0;
    for (AlignedAction aligned : ctx.alignedActions()) {
      long tick = aligned.action().sourceTick();
      FishingState state =
          FishingStateExtractor.extract(
              aligned.snapshot(), ctx.inventoryTotals(), ctx.initialInventoryTotal());
      Prediction prediction =
          FishingDemonstrationPolicy.predict(
              state, model.examples(), model.maxAcceptedPositionDistance());
      SemanticAction expected = FishingActionNormalizer.classify(aligned.action()).semanticAction();

      if (prediction.isAbstain()) {
        RunReport failed =
            new RunReport(
                "FAILED",
                newRunId(),
                modelId,
                model.trainingRecordingId(),
                recordingId,
                DECISION_TIMING_MODE,
                expectedCount,
                decisions.size(),
                0,
                1,
                ctx.catchDelta(),
                ctx.fishingXpIncreased(),
                decisions,
                "ABSTENTION",
                tick,
                expected,
                null,
                null,
                null);
        return persist(failed);
      }

      if (expected == null || !prediction.action().matches(expected)) {
        RunReport failed =
            new RunReport(
                "FAILED",
                newRunId(),
                modelId,
                model.trainingRecordingId(),
                recordingId,
                DECISION_TIMING_MODE,
                expectedCount,
                decisions.size(),
                1,
                0,
                ctx.catchDelta(),
                ctx.fishingXpIncreased(),
                decisions,
                "ACTION_MISMATCH",
                tick,
                expected,
                prediction.action(),
                null,
                null);
        return persist(failed);
      }

      decisions.add(
          new RunDecision(tick, expected, prediction.action(), true, prediction.targetNpc()));
      index++;
    }

    RunReport passed =
        new RunReport(
            "PASSED",
            newRunId(),
            modelId,
            model.trainingRecordingId(),
            recordingId,
            DECISION_TIMING_MODE,
            expectedCount,
            decisions.size(),
            0,
            0,
            ctx.catchDelta(),
            ctx.fishingXpIncreased(),
            decisions,
            null,
            null,
            null,
            null,
            null,
            null);
    return persist(passed);
  }

  private RunReport persist(RunReport report) {
    Path runsDir = Paths.get(outputDir, "fishing", "runs");
    Path reportPath = runsDir.resolve(report.runId() + ".json");
    try {
      Files.createDirectories(runsDir);
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
    } catch (IOException e) {
      throw new PrototypeApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "ARTIFACT_IO_FAILED",
          "Failed to persist run report",
          Map.of("path", reportPath.toString()));
    }
    log.info(
        "Fishing replay run {}: status={} for test recording {}",
        report.runId(),
        report.status(),
        report.testRecordingId());
    return new RunReport(
        report.status(),
        report.runId(),
        report.modelId(),
        report.trainingRecordingId(),
        report.testRecordingId(),
        report.decisionTimingMode(),
        report.expectedDecisionCount(),
        report.matchedDecisionCount(),
        report.mismatchCount(),
        report.abstentionCount(),
        report.observedCatchDelta(),
        report.fishingXpIncreased(),
        report.decisions(),
        report.failureCode(),
        report.failureTick(),
        report.expected(),
        report.predicted(),
        report.issues(),
        reportPath.toString());
  }

  private void ensureEnabled() {
    if (!aiPlayerEnabled) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST, "AI_PLAYER_DISABLED", "AI player feature is disabled");
    }
  }

  private static String newRunId() {
    return "fishing_run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }
}
