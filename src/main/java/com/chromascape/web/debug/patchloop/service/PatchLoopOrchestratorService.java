package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.event.PatchLoopIterationCreatedEvent;
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

  public PatchLoopOrchestratorService(
      PatchLoopIterationRepository iterationRepository,
      PatchLoopEventRepository eventRepository,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper) {
    this.iterationRepository = iterationRepository;
    this.eventRepository = eventRepository;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
  }

  /**
   * Executes a new patch loop iteration.
   *
   * @param request execution request
   * @return execution response with iteration ID
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

    // Create iteration
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("patchloop_" + UUID.randomUUID().toString().substring(0, 8));
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setScriptName(request.getScriptName());
    iteration.setPrompt(request.getPrompt());
    iteration.setContext(request.getContext());

    // Store evidence config if provided
    if (request.getEvidenceConfig() != null) {
      try {
        JsonNode configNode = objectMapper.valueToTree(request.getEvidenceConfig());
        iteration.setEvidenceConfig(configNode);
      } catch (Exception e) {
        logger.warn("Failed to serialize evidence config", e);
      }
    }

    iteration = iterationRepository.save(iteration);

    logEvent(
        iteration.getIterationId(),
        PatchLoopEventType.ITERATION_CREATED,
        "Iteration created: " + iteration.getIterationId());

    // Publish event - listener will trigger async processing AFTER transaction commits
    eventPublisher.publishEvent(new PatchLoopIterationCreatedEvent(iteration.getIterationId()));

    return new PatchLoopExecuteResponse(
        iteration.getIterationId(),
        iteration.getStatus().name(),
        "Iteration started, processing asynchronously");
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
