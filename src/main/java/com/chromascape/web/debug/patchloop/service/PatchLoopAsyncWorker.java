package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.exception.AnalysisException;
import com.chromascape.web.debug.patchloop.graph.GraphExecutionException;
import com.chromascape.web.debug.patchloop.graph.LoopGraph;
import com.chromascape.web.debug.patchloop.graph.PatchLoopGraphExecutor;
import com.chromascape.web.debug.patchloop.service.provider.AnalysisProvider;
import com.chromascape.web.debug.patchloop.websocket.PatchLoopWebSocketHandler;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.instance.RunConfig;
import com.chromascape.web.instance.ScriptControl;
import com.chromascape.web.instance.ScriptInstance;
import com.chromascape.web.instance.ScriptInstanceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Async worker service for patch loop iteration processing.
 *
 * <p>Separated from orchestrator to enable proper @Async behavior via cross-bean invocation.
 */
@Service
public class PatchLoopAsyncWorker {

  private static final Logger logger = LoggerFactory.getLogger(PatchLoopAsyncWorker.class);

  private static final int SCRIPT_RUN_TIMEOUT_MINUTES = 5;
  private static final int SCRIPT_POLL_INTERVAL_MS = 500;

  private final PatchLoopIterationRepository iterationRepository;
  private final PatchLoopEventRepository eventRepository;
  private final PatchLoopEvidenceService evidenceService;
  private final AnalysisProvider analysisProvider;
  private final DebugSessionRepository debugSessionRepository;
  private final ScriptControl scriptControl;
  private final ObjectMapper objectMapper;
  private final PatchLoopPersistenceService persistenceService;
  private final PatchLoopGraphExecutor graphExecutor;
  private final PatchLoopWebSocketHandler webSocketHandler;

  public PatchLoopAsyncWorker(
      PatchLoopIterationRepository iterationRepository,
      PatchLoopEventRepository eventRepository,
      PatchLoopEvidenceService evidenceService,
      AnalysisProvider analysisProvider,
      DebugSessionRepository debugSessionRepository,
      ScriptControl scriptControl,
      ObjectMapper objectMapper,
      PatchLoopPersistenceService persistenceService,
      PatchLoopGraphExecutor graphExecutor,
      PatchLoopWebSocketHandler webSocketHandler) {
    this.iterationRepository = iterationRepository;
    this.eventRepository = eventRepository;
    this.evidenceService = evidenceService;
    this.analysisProvider = analysisProvider;
    this.debugSessionRepository = debugSessionRepository;
    this.scriptControl = scriptControl;
    this.objectMapper = objectMapper;
    this.persistenceService = persistenceService;
    this.graphExecutor = graphExecutor;
    this.webSocketHandler = webSocketHandler;

    logger.info(
        "PatchLoopAsyncWorker initialized with analysis provider: {}",
        analysisProvider.getProviderName());
  }

  /**
   * Processes an iteration asynchronously.
   *
   * <p>Note: No @Transactional here to prevent long transactions. Delegates to {@link
   * PatchLoopPersistenceService} for focused DB writes with proper transaction boundaries.
   *
   * @param iterationId the iteration ID
   */
  @Async("patchLoopExecutor")
  public void processIterationAsync(String iterationId) {
    logger.info(
        "[{}] Starting async processing for iteration: {}",
        Thread.currentThread().getName(),
        iterationId);

    Optional<PatchLoopIteration> optIteration = iterationRepository.findByIterationId(iterationId);
    if (optIteration.isEmpty()) {
      logger.error("Iteration {} not found", iterationId);
      return;
    }

    PatchLoopIteration iteration = optIteration.get();

    try {
      // State 1: RUNNING_SCRIPT
      persistenceService.updateIterationStatus(iteration, PatchLoopStatus.RUNNING_SCRIPT);
      logEvent(iterationId, PatchLoopEventType.SCRIPT_STARTED, "Starting script run");

      triggerScriptRun(iteration);

      if (iteration.getRunSuccess() == null || !iteration.getRunSuccess()) {
        // Fix #2: Transition RUN_FAILED → FAILED immediately
        String reason =
            iteration.getFailureReason() != null
                ? iteration.getFailureReason()
                : "Script run failed";
        persistenceService.markIterationFailed(
            iteration,
            iteration.getFailureCode() != null
                ? iteration.getFailureCode()
                : PatchLoopFailureCode.SCRIPT_EXCEPTION,
            reason,
            iteration.getFailureContext());
        logEvent(iterationId, PatchLoopEventType.ITERATION_FAILED, reason, "ERROR");
        return;
      }

      persistenceService.updateIterationStatus(iteration, PatchLoopStatus.RUN_COMPLETED);
      logEvent(iterationId, PatchLoopEventType.SCRIPT_COMPLETED, "Script run completed");

      // State 2: COLLECTING_EVIDENCE
      persistenceService.updateIterationStatus(iteration, PatchLoopStatus.COLLECTING_EVIDENCE);
      logEvent(iterationId, PatchLoopEventType.EVIDENCE_COLLECTION_STARTED, "Collecting evidence");

      AnalysisV1EvidenceBundle evidence = collectEvidence(iteration);

      if (!evidenceService.validateEvidenceSufficiency(evidence)) {
        // Fix #5: Don't overwrite INSUFFICIENT_EVIDENCE with markFailed()
        persistenceService.updateIterationStatus(iteration, PatchLoopStatus.INSUFFICIENT_EVIDENCE);
        persistenceService.saveInsufficientEvidence(iteration, evidence);
        logEvent(
            iterationId,
            PatchLoopEventType.EVIDENCE_INSUFFICIENT,
            "Insufficient evidence found",
            "WARN");
        return;
      }

      persistenceService.updateIterationStatus(iteration, PatchLoopStatus.EVIDENCE_COLLECTED);
      persistenceService.saveEvidenceCounts(iteration, evidence);
      logEvent(
          iterationId,
          PatchLoopEventType.EVIDENCE_COLLECTED,
          "Evidence collected: "
              + evidence.getLogs().size()
              + " logs, "
              + evidence.getTickLogs().size()
              + " tick logs, "
              + evidence.getDecisions().size()
              + " decisions");

      // State 3: ANALYZING
      persistenceService.updateIterationStatus(iteration, PatchLoopStatus.ANALYZING);
      logEvent(iterationId, PatchLoopEventType.ANALYSIS_STARTED, "Starting Claude analysis");

      long startTime = System.currentTimeMillis();
      analyzeEvidence(iteration, evidence);
      long analysisTime = System.currentTimeMillis() - startTime;
      iteration.setAnalysisTimeMs(analysisTime);

      persistenceService.updateIterationStatus(iteration, PatchLoopStatus.ANALYZED);
      logEvent(
          iterationId,
          PatchLoopEventType.ANALYSIS_COMPLETED,
          "Analysis completed with " + iteration.getRecommendationCount() + " recommendations");

      // State 4: COMPLETED
      persistenceService.updateIterationStatus(iteration, PatchLoopStatus.COMPLETED);
      logEvent(iterationId, PatchLoopEventType.ITERATION_COMPLETED, "Iteration completed");

    } catch (Exception e) {
      logger.error("Error processing iteration {}", iterationId, e);

      // Fix #2: Only set INTERNAL_ERROR if no specific failure code already set
      if (!iteration.getStatus().isTerminal() && iteration.getFailureCode() == null) {
        persistenceService.markIterationFailed(
            iteration,
            PatchLoopFailureCode.INTERNAL_ERROR,
            "Unexpected error: " + e.getMessage(),
            stackTraceToString(e));
      } else {
        // Already failed with specific code - just ensure it's saved and logged
        persistenceService.saveIteration(iteration);
      }

      logEvent(
          iterationId,
          PatchLoopEventType.ITERATION_FAILED,
          "Iteration failed: "
              + (iteration.getFailureReason() != null
                  ? iteration.getFailureReason()
                  : e.getMessage()),
          "ERROR");
    }
  }

  /**
   * Executes a graph asynchronously with true async behavior.
   *
   * <p>Uses @Async annotation to run in a separate thread pool, allowing the HTTP response to
   * return immediately while execution continues.
   *
   * @param executionId the execution ID
   * @param graph the compiled graph
   * @param context the execution context
   * @return CompletableFuture that completes when execution finishes
   */
  @Async("patchLoopExecutor")
  public CompletableFuture<Void> executeGraphAsync(
      String executionId, LoopGraph graph, GraphExecutionContext context) {
    try {
      logger.info("[{}] Starting async graph execution", Thread.currentThread().getName());
      graphExecutor.executeGraph(executionId, graph, context);
      return CompletableFuture.completedFuture(null);
    } catch (GraphExecutionException e) {
      logger.error("Async graph execution failed: {}", executionId, e);
      webSocketHandler.broadcastIterationFailed(executionId, e.getMessage());
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Resumes a paused execution asynchronously.
   *
   * <p>Uses @Async annotation to run in a separate thread pool, allowing the HTTP response to
   * return immediately while execution continues.
   *
   * @param executionId the execution ID
   * @return CompletableFuture that completes when resume finishes
   */
  @Async("patchLoopExecutor")
  public CompletableFuture<Void> resumeExecutionAsync(String executionId) {
    try {
      logger.info("[{}] Starting async resume execution", Thread.currentThread().getName());
      graphExecutor.resumeExecution(executionId);
      return CompletableFuture.completedFuture(null);
    } catch (GraphExecutionException e) {
      logger.error("Async resume execution failed: {}", executionId, e);
      webSocketHandler.broadcastIterationFailed(executionId, e.getMessage());
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Retries execution from a specific node asynchronously.
   *
   * <p>Uses @Async annotation to run in a separate thread pool, allowing the HTTP response to
   * return immediately while execution continues.
   *
   * @param executionId the execution ID
   * @param nodeId the node ID to retry from
   * @return CompletableFuture that completes when retry finishes
   */
  @Async("patchLoopExecutor")
  public CompletableFuture<Void> retryFromNodeAsync(String executionId, String nodeId) {
    try {
      logger.info(
          "[{}] Starting async retry from node: {}", Thread.currentThread().getName(), nodeId);
      graphExecutor.retryFromNode(executionId, nodeId);
      return CompletableFuture.completedFuture(null);
    } catch (GraphExecutionException e) {
      logger.error("Async retry from node failed: {}, node: {}", executionId, nodeId, e);
      webSocketHandler.broadcastIterationFailed(executionId, e.getMessage());
      return CompletableFuture.failedFuture(e);
    }
  }

  /** Triggers a script run and waits for completion. */
  private void triggerScriptRun(PatchLoopIteration iteration) {
    logger.info("Triggering script run for: {}", iteration.getScriptName());

    try {
      // Trigger script via ScriptControl
      RunConfig config = new RunConfig(iteration.getScriptName());
      ResponseEntity<Object> response = scriptControl.getRunConfig(config);

      if (!response.getStatusCode().is2xxSuccessful()) {
        iteration.setRunSuccess(false);
        iteration.setFailureCode(PatchLoopFailureCode.SCRIPT_START_FAILED);
        iteration.setFailureReason("Failed to start script: " + response.getBody());
        return;
      }

      // Wait for script to start
      Thread.sleep(1000);

      // Poll for script completion
      LocalDateTime startTime = LocalDateTime.now();
      iteration.setRunStartTime(startTime);

      ScriptInstance instance = ScriptInstanceManager.getInstance().getInstanceRef();
      if (instance == null) {
        iteration.setRunSuccess(false);
        iteration.setFailureCode(PatchLoopFailureCode.SCRIPT_START_FAILED);
        iteration.setFailureReason("Script instance is null after start");
        return;
      }

      boolean timeout = false;
      while (instance.isRunning()) {
        Thread.sleep(SCRIPT_POLL_INTERVAL_MS);

        Duration elapsed = Duration.between(startTime, LocalDateTime.now());
        if (elapsed.toMinutes() >= SCRIPT_RUN_TIMEOUT_MINUTES) {
          logger.warn("Script run timed out after {} minutes", SCRIPT_RUN_TIMEOUT_MINUTES);
          instance.stop();
          timeout = true;
          break;
        }
      }

      LocalDateTime endTime = LocalDateTime.now();
      iteration.setRunEndTime(endTime);

      // Find debug session
      Optional<DebugSession> session =
          debugSessionRepository.findFirstByScriptNameOrderByStartTimeDesc(
              iteration.getScriptName());
      if (session.isPresent()) {
        iteration.setRunSessionId(session.get().getSessionId());
        if (session.get().getRecordingId() != null) {
          iteration.setRunRecordingId(session.get().getRecordingId());
        }
        iteration.setRunSuccess(!timeout && session.get().getFailureCode() == null);
      } else {
        logger.warn("No debug session found for script: {}", iteration.getScriptName());
        iteration.setRunSuccess(!timeout);
      }

      if (timeout) {
        iteration.setFailureCode(PatchLoopFailureCode.SCRIPT_RUN_TIMEOUT);
        iteration.setFailureReason(
            "Script run exceeded timeout of " + SCRIPT_RUN_TIMEOUT_MINUTES + " minutes");
      }

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      iteration.setRunSuccess(false);
      iteration.setFailureCode(PatchLoopFailureCode.SCRIPT_EXCEPTION);
      iteration.setFailureReason("Script run interrupted: " + e.getMessage());
    } catch (Exception e) {
      logger.error("Error triggering script run", e);
      iteration.setRunSuccess(false);
      iteration.setFailureCode(PatchLoopFailureCode.SCRIPT_EXCEPTION);
      iteration.setFailureReason("Script error: " + e.getMessage());
      iteration.setFailureContext(stackTraceToString(e));
    }
  }

  /** Collects evidence for the iteration. */
  private AnalysisV1EvidenceBundle collectEvidence(PatchLoopIteration iteration) {
    logger.info("Collecting evidence for iteration: {}", iteration.getIterationId());

    try {
      // Fix #4: Deserialize and pass evidence config
      EvidenceConfigDto config = null;
      if (iteration.getEvidenceConfig() != null) {
        try {
          config = objectMapper.treeToValue(iteration.getEvidenceConfig(), EvidenceConfigDto.class);
        } catch (Exception e) {
          logger.warn("Failed to parse evidence config, using defaults", e);
        }
      }

      return evidenceService.collectEvidence(iteration, config);
    } catch (Exception e) {
      logger.error("Evidence collection failed", e);
      iteration.markFailed(
          PatchLoopFailureCode.EVIDENCE_COLLECTION_FAILED,
          "Failed to collect evidence: " + e.getMessage(),
          stackTraceToString(e));
      throw e;
    }
  }

  /** Analyzes evidence with Claude API using V2 contract (long-running, outside transaction). */
  private void analyzeEvidence(PatchLoopIteration iteration, AnalysisV1EvidenceBundle evidence) {
    logger.info(
        "Analyzing evidence for iteration: {} using provider: {}",
        iteration.getIterationId(),
        analysisProvider.getProviderName());

    try {
      // Build V2 analysis request from V1 evidence bundle
      AnalysisV2Request request = buildV2Request(iteration, evidence);

      // Store request JSON
      JsonNode requestNode = objectMapper.valueToTree(request);
      persistenceService.saveAnalysisRequest(iteration, requestNode);

      // Call analysis provider (long-running, outside any transaction)
      AnalysisV2Response response = analysisProvider.analyzeEvidence(request);

      // Store response JSON and extract V2 diagnosis fields
      JsonNode responseNode = objectMapper.valueToTree(response);
      persistenceService.saveAnalysisResults(iteration, response, responseNode);

    } catch (AnalysisException e) {
      logger.error("Analysis failed: {} - {}", e.getErrorCode(), e.getMessage());
      iteration.markFailed(
          "ANALYSIS_" + e.getErrorCode(),
          "Analysis failed: " + e.getMessage(),
          stackTraceToString(e));
      throw new RuntimeException(e);
    } catch (Exception e) {
      logger.error("Unexpected error during analysis", e);
      iteration.markFailed(
          PatchLoopFailureCode.ANALYSIS_API_ERROR,
          "Analysis error: " + e.getMessage(),
          stackTraceToString(e));
      throw e;
    }
  }

  /** Builds a V2 analysis request from iteration and V1 evidence bundle. */
  private AnalysisV2Request buildV2Request(
      PatchLoopIteration iteration, AnalysisV1EvidenceBundle evidence) {

    AnalysisV2Request request = new AnalysisV2Request();

    // Set prompt type based on iteration goal
    request.setPromptType(
        iteration.getPrompt() != null && iteration.getPrompt().toLowerCase().contains("feature")
            ? PromptType.FEATURE
            : PromptType.ISSUE);

    // Build target
    AnalysisV2Target target = new AnalysisV2Target();
    target.setScriptName(iteration.getScriptName());
    target.setRunId(iteration.getIterationId());

    // Set session bounds from evidence time window
    if (evidence.getTimeWindow() != null) {
      AnalysisV2Target.SessionBounds bounds = new AnalysisV2Target.SessionBounds();
      bounds.setStart(evidence.getTimeWindow().getStart());
      bounds.setEnd(evidence.getTimeWindow().getEnd());
      target.setSessionBounds(bounds);
    }
    request.setTarget(target);

    // Set prompt and context
    request.setPrompt(iteration.getPrompt());
    request.setContext(iteration.getContext());

    // Build evidence selectors from V1 evidence bundle types
    java.util.List<AnalysisV2EvidenceSelector> selectors = new java.util.ArrayList<>();

    // Add log selector if logs present
    if (!evidence.getLogs().isEmpty()) {
      AnalysisV2EvidenceSelector logSelector =
          new AnalysisV2EvidenceSelector(EvidenceType.GENERAL_LOGS);
      logSelector.setMaxCount(evidence.getLogs().size());
      if (evidence.getTimeWindow() != null) {
        logSelector.setTimeWindow(evidence.getTimeWindow());
      }
      selectors.add(logSelector);
    }

    // Add tick log selector if tick logs present
    if (!evidence.getTickLogs().isEmpty()) {
      AnalysisV2EvidenceSelector tickSelector =
          new AnalysisV2EvidenceSelector(EvidenceType.TICK_LOGS);
      tickSelector.setMaxCount(evidence.getTickLogs().size());
      if (evidence.getTickWindow() != null) {
        tickSelector.setTickWindow(evidence.getTickWindow());
      }
      selectors.add(tickSelector);
    }

    // Add decision selector if decisions present
    if (!evidence.getDecisions().isEmpty()) {
      AnalysisV2EvidenceSelector decisionSelector =
          new AnalysisV2EvidenceSelector(EvidenceType.DECISIONS);
      decisionSelector.setMaxCount(evidence.getDecisions().size());
      if (evidence.getTimeWindow() != null) {
        decisionSelector.setTimeWindow(evidence.getTimeWindow());
      }
      selectors.add(decisionSelector);
    }

    request.setEvidenceSelectors(selectors);

    return request;
  }

  // ==================== Non-Transactional Helper Methods ====================

  /** Logs an event for an iteration. */
  private void logEvent(String iterationId, PatchLoopEventType type, String message) {
    logEvent(iterationId, type, message, "INFO");
  }

  /** Logs an event with severity. */
  private void logEvent(
      String iterationId, PatchLoopEventType type, String message, String severity) {
    try {
      PatchLoopEvent event = new PatchLoopEvent(iterationId, type, message, severity);
      eventRepository.save(event);
    } catch (Exception e) {
      logger.error("Failed to log event", e);
    }
  }

  /** Converts exception stack trace to string. */
  private String stackTraceToString(Throwable t) {
    java.io.StringWriter sw = new java.io.StringWriter();
    t.printStackTrace(new java.io.PrintWriter(sw));
    return sw.toString();
  }
}
