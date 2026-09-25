package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.entity.PatchLoopAnalysisArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopAnalysisArtifactRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEvidenceArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopEvidenceArtifactRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopPatchArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopPatchArtifactRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopRetestArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopRetestArtifactRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopValidationArtifact;
import com.chromascape.web.debug.patchloop.entity.PatchLoopValidationArtifactRepository;
import com.chromascape.web.debug.patchloop.graph.GraphCompilationException;
import com.chromascape.web.debug.patchloop.graph.GraphExecutionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Main orchestrator service for patch loop iterations.
 *
 * <p>Manages the full lifecycle: script run → evidence collection → analysis → recommendations.
 */
@Service
public class PatchLoopOrchestratorService {

  private static final Logger logger = LoggerFactory.getLogger(PatchLoopOrchestratorService.class);

  private final PatchLoopIterationRepository iterationRepository;
  private final PatchLoopEventRepository eventRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final PatchLoopGraphOrchestratorService graphOrchestratorService;
  private final PatchLoopAnalysisArtifactRepository analysisArtifactRepository;
  private final PatchLoopPatchArtifactRepository patchArtifactRepository;
  private final PatchLoopValidationArtifactRepository validationArtifactRepository;
  private final PatchLoopRetestArtifactRepository retestArtifactRepository;
  private final PatchLoopEvidenceArtifactRepository evidenceArtifactRepository;
  private final PatchLoopSessionResolverService sessionResolverService;

  public PatchLoopOrchestratorService(
      PatchLoopIterationRepository iterationRepository,
      PatchLoopEventRepository eventRepository,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      PatchLoopGraphOrchestratorService graphOrchestratorService,
      PatchLoopAnalysisArtifactRepository analysisArtifactRepository,
      PatchLoopPatchArtifactRepository patchArtifactRepository,
      PatchLoopValidationArtifactRepository validationArtifactRepository,
      PatchLoopRetestArtifactRepository retestArtifactRepository,
      PatchLoopEvidenceArtifactRepository evidenceArtifactRepository,
      PatchLoopSessionResolverService sessionResolverService) {
    this.iterationRepository = iterationRepository;
    this.eventRepository = eventRepository;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.graphOrchestratorService = graphOrchestratorService;
    this.analysisArtifactRepository = analysisArtifactRepository;
    this.patchArtifactRepository = patchArtifactRepository;
    this.validationArtifactRepository = validationArtifactRepository;
    this.retestArtifactRepository = retestArtifactRepository;
    this.evidenceArtifactRepository = evidenceArtifactRepository;
    this.sessionResolverService = sessionResolverService;
  }

  /**
   * Executes a new patch loop iteration.
   *
   * <p>E6.5: This method now delegates to the graph orchestrator using the default BMAD template,
   * ensuring consistent behavior across both simple and graph execution paths.
   *
   * @param request execution request
   * @return execution response with iteration ID, execution ID, WebSocket URL, and API URL
   */
  @Transactional
  public PatchLoopExecuteResponse executeIteration(PatchLoopExecuteRequest request) {
    logger.info("Executing patch loop iteration for script: {}", request.getScriptName());

    // Validate request
    if (request.getScriptName() == null || request.getScriptName().trim().isEmpty()) {
      throw new IllegalArgumentException("Script name is required");
    }
    if (request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
      throw new IllegalArgumentException("Prompt is required");
    }

    // Create iteration with all fields
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("patchloop_" + UUID.randomUUID().toString().substring(0, 8));
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setScriptName(request.getScriptName());
    iteration.setPrompt(request.getPrompt());
    iteration.setContext(request.getContext());

    // Persist E6.5+ fields
    iteration.setPromptType(
        request.getPromptType() != null ? request.getPromptType().name() : "ISSUE");
    iteration.setExecutionMode(request.getNormalizedExecutionMode());
    iteration.setMaxAttempts(request.getMaxAttempts() != null ? request.getMaxAttempts() : 3);

    if (request.getSuccessCriteria() != null) {
      try {
        iteration.setSuccessCriteria(objectMapper.valueToTree(request.getSuccessCriteria()));
      } catch (Exception e) {
        logger.warn("Failed to serialize success criteria", e);
      }
    }

    if (request.getSessionContext() != null) {
      try {
        iteration.setSessionContext(objectMapper.valueToTree(request.getSessionContext()));
      } catch (Exception e) {
        logger.warn("Failed to serialize session context", e);
      }
    }

    var resolvedSession = sessionResolverService.resolveForIteration(iteration);
    if (resolvedSession.isPresent()) {
      sessionResolverService.applyResolvedSession(iteration, resolvedSession.get());
    }

    // Store evidence config if provided
    if (request.getEvidenceConfig() != null) {
      try {
        JsonNode configNode = objectMapper.valueToTree(request.getEvidenceConfig());
        iteration.setEvidenceConfig(configNode);
      } catch (Exception e) {
        logger.warn("Failed to serialize evidence config", e);
      }
    }

    // Store task plan context if provided
    if (request.getTaskPlanId() != null) {
      iteration.setTaskPlanId(request.getTaskPlanId());
    }
    if (request.getTaskItemId() != null) {
      iteration.setTaskItemId(request.getTaskItemId());
    }

    iteration = iterationRepository.save(iteration);

    logEvent(
        iteration.getIterationId(),
        PatchLoopEventType.ITERATION_CREATED,
        "Iteration created: " + iteration.getIterationId());

    // Create GraphExecuteRequest using default BMAD template
    GraphExecuteRequest graphRequest = new GraphExecuteRequest();
    graphRequest.setMermaid(DefaultBmadGraphTemplate.getTemplate());
    graphRequest.setScriptName(request.getScriptName());
    graphRequest.setGoalPrompt(request.getPrompt());
    graphRequest.setContext(request.getContext());
    graphRequest.setExecutionMode(request.getNormalizedExecutionMode());
    graphRequest.setMaxAttempts(request.getMaxAttempts());
    graphRequest.setSuccessCriteria(request.getSuccessCriteria());
    graphRequest.setSessionContext(request.getSessionContext());
    graphRequest.setEvidenceConfig(request.getEvidenceConfig());
    graphRequest.setTaskMetadata(request.getTaskMetadata());
    graphRequest.setIterationId(iteration.getIterationId()); // Pass iterationId to reuse iteration

    // Delegate to graph orchestrator
    GraphExecuteResponse graphResponse;
    try {
      graphResponse = graphOrchestratorService.executeGraph(graphRequest);
    } catch (GraphCompilationException | GraphExecutionException e) {
      logger.error("Graph execution failed", e);
      // Mark iteration as failed
      iteration.setStatus(PatchLoopStatus.FAILED);
      iteration.setFailureCode(PatchLoopFailureCode.INTERNAL_ERROR);
      iteration.setFailureReason("Failed to execute graph: " + e.getMessage());
      iterationRepository.save(iteration);

      logEvent(
          iteration.getIterationId(),
          PatchLoopEventType.ITERATION_FAILED,
          "Graph execution failed: " + e.getMessage(),
          "ERROR");
      throw new RuntimeException("Failed to execute patch loop", e);
    }

    // Map response back to simple API format
    PatchLoopExecuteResponse response = new PatchLoopExecuteResponse();
    response.setIterationId(iteration.getIterationId());
    response.setExecutionId(graphResponse.getExecutionId());
    response.setStatus(iteration.getStatus().name());
    response.setMessage("Iteration started, processing asynchronously");
    response.setWsUrl(graphResponse.getWsUrl());
    response.setApiUrl(graphResponse.getApiUrl());

    // Note: Graph orchestrator already triggers async execution, so we don't publish event here

    return response;
  }

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

  /**
   * Gets an iteration by ID.
   *
   * @param iterationId iteration ID
   * @return iteration response
   */
  @Transactional(readOnly = true)
  public Optional<PatchLoopIterationResponse> getIteration(String iterationId) {
    return iterationRepository.findByIterationId(iterationId).map(this::toIterationResponse);
  }

  /**
   * Gets events for an iteration.
   *
   * @param iterationId iteration ID
   * @return list of events
   */
  @Transactional(readOnly = true)
  public List<PatchLoopEventResponse> getEvents(String iterationId) {
    return eventRepository.findByIterationIdOrderByTimestampAsc(iterationId).stream()
        .map(this::toEventResponse)
        .collect(Collectors.toList());
  }

  /**
   * Lists iterations with optional filters.
   *
   * @param scriptName filter by script name (optional)
   * @param status filter by status (optional)
   * @return list of iterations
   */
  @Transactional(readOnly = true)
  public List<PatchLoopIterationResponse> listIterations(
      String scriptName, PatchLoopStatus status) {
    List<PatchLoopIteration> iterations;

    if (scriptName != null && status != null) {
      iterations =
          iterationRepository.findByScriptNameAndStatusOrderByCreatedAtDesc(scriptName, status);
    } else if (scriptName != null) {
      iterations = iterationRepository.findByScriptNameOrderByCreatedAtDesc(scriptName);
    } else if (status != null) {
      iterations = iterationRepository.findByStatusOrderByCreatedAtDesc(status);
    } else {
      iterations = iterationRepository.findAllByOrderByCreatedAtDesc();
    }

    return iterations.stream().map(this::toIterationResponse).collect(Collectors.toList());
  }

  /**
   * Lists active (non-terminal) iterations using optimized query.
   *
   * @return list of active iterations
   */
  @Transactional(readOnly = true)
  public List<PatchLoopIterationResponse> listActiveIterations() {
    return iterationRepository.findActiveIterations().stream()
        .map(this::toIterationResponse)
        .collect(Collectors.toList());
  }

  /** Converts entity to response DTO. */
  private PatchLoopIterationResponse toIterationResponse(PatchLoopIteration iteration) {
    PatchLoopIterationResponse response = new PatchLoopIterationResponse();
    response.setIterationId(iteration.getIterationId());
    response.setStatus(iteration.getStatus().name());
    response.setScriptName(iteration.getScriptName());
    response.setPrompt(iteration.getPrompt());
    response.setContext(iteration.getContext());
    response.setCreatedAt(iteration.getCreatedAt());
    response.setLastStateAt(iteration.getLastStateAt());
    response.setCompletedAt(iteration.getCompletedAt());
    response.setRunSessionId(iteration.getRunSessionId());
    response.setRunRecordingId(iteration.getRunRecordingId());

    if (iteration.getRunStartTime() != null && iteration.getRunEndTime() != null) {
      response.setRunDurationMs(
          Duration.between(iteration.getRunStartTime(), iteration.getRunEndTime()).toMillis());
    }

    response.setRunSuccess(iteration.getRunSuccess());
    response.setEvidenceLogCount(iteration.getEvidenceLogCount());
    response.setEvidenceTickLogCount(iteration.getEvidenceTickLogCount());
    response.setEvidenceDecisionCount(iteration.getEvidenceDecisionCount());
    response.setAnalysisId(iteration.getAnalysisId());
    response.setDiagnosisSummary(iteration.getDiagnosisSummary());
    response.setRootCause(iteration.getRootCause());
    response.setConfidence(iteration.getConfidence());
    response.setRecommendationCount(iteration.getRecommendationCount());
    response.setAnalysisTimeMs(iteration.getAnalysisTimeMs());
    response.setFailureCode(iteration.getFailureCode());
    response.setFailureReason(iteration.getFailureReason());
    response.setIsActive(iteration.isActive());
    response.setIsComplete(iteration.isTerminal());

    // E6.5+: Populate new fields
    response.setExecutionMode(iteration.getExecutionMode());
    response.setMaxAttempts(iteration.getMaxAttempts());
    response.setCurrentAttempt(iteration.getCurrentAttempt());

    if (iteration.getSessionContext() != null) {
      try {
        response.setSessionContext(
            objectMapper.treeToValue(iteration.getSessionContext(), SessionContextDto.class));
      } catch (Exception e) {
        logger.warn("Failed to deserialize session context", e);
      }
    }

    // Populate artifact and retest summaries
    String executionId = iteration.getGraphExecutionId();
    if (executionId != null) {
      ArtifactSummaryDto artifactSummary = new ArtifactSummaryDto();

      // Analysis artifacts
      java.util.List<PatchLoopAnalysisArtifact> analyses =
          analysisArtifactRepository.findByExecutionIdOrderByTimestampAsc(executionId);
      artifactSummary.setAnalysisCount(analyses.size());
      if (!analyses.isEmpty()) {
        artifactSummary.setLatestAnalysisId(analyses.get(analyses.size() - 1).getAnalysisId());
      }

      // Patch artifacts
      java.util.List<PatchLoopPatchArtifact> patches =
          patchArtifactRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);
      artifactSummary.setPatchCount(patches.size());
      if (!patches.isEmpty()) {
        artifactSummary.setLatestPatchId(patches.get(patches.size() - 1).getPatchId());
      }

      // Validation artifacts
      java.util.List<PatchLoopValidationArtifact> validations =
          validationArtifactRepository.findByExecutionIdOrderByTimestampAsc(executionId);
      artifactSummary.setValidationCount(validations.size());

      // Retest artifacts (count for artifact summary)
      java.util.List<PatchLoopRetestArtifact> retests =
          retestArtifactRepository.findByExecutionIdOrderByTimestampAsc(executionId);
      artifactSummary.setRetestCount(retests.size());

      // Evidence artifacts
      java.util.List<PatchLoopEvidenceArtifact> evidence =
          evidenceArtifactRepository.findByExecutionIdOrderByTimestampAsc(executionId);
      artifactSummary.setEvidenceCount(evidence.size());

      response.setArtifactSummary(artifactSummary);

      // Populate retest summary
      RetestSummaryDto retestSummary = new RetestSummaryDto();
      retestSummary.setTotalRetests(retests.size());

      int successfulCount = 0;
      int failedCount = 0;
      Boolean lastRetestSuccess = null;
      Integer improvementsCount = 0;
      Integer regressionsCount = 0;

      for (PatchLoopRetestArtifact retest : retests) {
        if (retest.getSuccess() != null && retest.getSuccess()) {
          successfulCount++;
          lastRetestSuccess = true;
        } else {
          failedCount++;
          lastRetestSuccess = false;
        }

        // Parse improvements and regressions as integers
        if (retest.getImprovements() != null && !retest.getImprovements().isBlank()) {
          try {
            improvementsCount += Integer.parseInt(retest.getImprovements().trim());
          } catch (NumberFormatException e) {
            // Ignore if not a number
          }
        }
        if (retest.getRegressions() != null && !retest.getRegressions().isBlank()) {
          try {
            regressionsCount += Integer.parseInt(retest.getRegressions().trim());
          } catch (NumberFormatException e) {
            // Ignore if not a number
          }
        }
      }

      retestSummary.setSuccessfulRetests(successfulCount);
      retestSummary.setFailedRetests(failedCount);
      retestSummary.setLastRetestSuccess(lastRetestSuccess);
      retestSummary.setImprovementsCount(improvementsCount);
      retestSummary.setRegressionsCount(regressionsCount);

      response.setRetestSummary(retestSummary);
    } else {
      // Set empty summaries (not null) for consistency when no execution exists
      ArtifactSummaryDto artifactSummary = new ArtifactSummaryDto();
      artifactSummary.setAnalysisCount(0);
      artifactSummary.setPatchCount(0);
      artifactSummary.setValidationCount(0);
      artifactSummary.setRetestCount(0);
      artifactSummary.setEvidenceCount(0);
      response.setArtifactSummary(artifactSummary);

      RetestSummaryDto retestSummary = new RetestSummaryDto();
      retestSummary.setTotalRetests(0);
      retestSummary.setSuccessfulRetests(0);
      retestSummary.setFailedRetests(0);
      response.setRetestSummary(retestSummary);
    }

    return response;
  }

  /** Converts event entity to response DTO. */
  private PatchLoopEventResponse toEventResponse(PatchLoopEvent event) {
    PatchLoopEventResponse response = new PatchLoopEventResponse();
    response.setEventType(event.getEventType().name());
    response.setTimestamp(event.getTimestamp());
    response.setMessage(event.getMessage());
    response.setSeverity(event.getSeverity());

    if (event.getData() != null) {
      try {
        response.setData(objectMapper.convertValue(event.getData(), java.util.Map.class));
      } catch (Exception e) {
        logger.warn("Failed to convert event data", e);
      }
    }

    return response;
  }
}
