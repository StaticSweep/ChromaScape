package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.graph.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrator for Mermaid-defined patch loop graph execution.
 *
 * <p>Handles graph compilation, execution lifecycle, and state management.
 *
 * <p>This service is now Spring-managed with full dependency injection.
 */
@Service
public class PatchLoopGraphOrchestratorService {

  private static final Logger logger =
      LoggerFactory.getLogger(PatchLoopGraphOrchestratorService.class);

  private final PatchLoopOrchestratorService orchestratorService;
  private final PatchLoopGraphCompiler compiler;
  private final PatchLoopGraphExecutor executor;
  private final PatchLoopGraphExecutionRepository executionRepository;
  private final PatchLoopIterationRepository iterationRepository;
  private final PatchLoopGraphNodeRepository nodeRepository;
  private final PatchLoopAttemptRepository attemptRepository;
  private final PatchLoopPatchArtifactRepository patchRepository;
  private final PatchLoopAnalysisClient analysisClient;
  private final PatchLoopEvidenceService evidenceService;
  private final PatchLoopWebSocketHandler webSocketHandler;
  private final ObjectMapper objectMapper;

  @Autowired
  public PatchLoopGraphOrchestratorService(
      PatchLoopOrchestratorService orchestratorService,
      PatchLoopGraphCompiler compiler,
      PatchLoopGraphExecutor executor,
      PatchLoopGraphExecutionRepository executionRepository,
      PatchLoopIterationRepository iterationRepository,
      PatchLoopGraphNodeRepository nodeRepository,
      PatchLoopAttemptRepository attemptRepository,
      PatchLoopPatchArtifactRepository patchRepository,
      PatchLoopAnalysisClient analysisClient,
      PatchLoopEvidenceService evidenceService,
      PatchLoopWebSocketHandler webSocketHandler,
      ObjectMapper objectMapper) {
    this.orchestratorService = orchestratorService;
    this.compiler = compiler;
    this.executor = executor;
    this.executionRepository = executionRepository;
    this.iterationRepository = iterationRepository;
    this.nodeRepository = nodeRepository;
    this.attemptRepository = attemptRepository;
    this.patchRepository = patchRepository;
    this.analysisClient = analysisClient;
    this.evidenceService = evidenceService;
    this.webSocketHandler = webSocketHandler;
    this.objectMapper = objectMapper;
  }

  /**
   * Executes a Mermaid-defined patch loop graph.
   *
   * @param request the graph execute request
   * @return the graph execute response
   */
  @Transactional
  public GraphExecuteResponse executeGraph(GraphExecuteRequest request)
      throws GraphCompilationException, GraphExecutionException {

    logger.info("Executing graph for script: {}", request.getScriptName());

    // Compile the graph
    LoopGraph graph = compiler.compile(request.getMermaid());

    // Create IDs
    String executionId = "graph_exec_" + UUID.randomUUID().toString().substring(0, 8);

    // Create or get iteration
    PatchLoopIteration iteration = createIteration(request);

    // Create graph execution record
    PatchLoopGraphExecution execution = new PatchLoopGraphExecution();
    execution.setExecutionId(executionId);
    execution.setIterationId(iteration.getIterationId());
    execution.setMermaidSource(request.getMermaid());
    execution.setGraphJson(serializeGraph(graph));
    execution.setCurrentNodeId(graph.getEntryNodeId());
    execution.setPolicyJson(serializePolicy(request.getPolicy()));
    executionRepository.save(execution);

    // Update iteration with graph execution ID
    iteration.setGraphExecutionId(executionId);
    iteration.setExecutionMode(request.getExecutionMode());
    iteration.setMaxAttempts(request.getMaxAttempts());
    iteration.setSuccessCriteria(
        request.getSuccessCriteria() != null
            ? objectMapper.valueToTree(request.getSuccessCriteria())
            : null);
    iterationRepository.save(iteration);

    // Build execution context
    GraphExecutionContext context =
        GraphExecutionContext.builder()
            .executionId(executionId)
            .iterationId(iteration.getIterationId())
            .scriptName(request.getScriptName())
            .goalPrompt(request.getGoalPrompt())
            .context(request.getContext())
            .maxAttempts(request.getMaxAttempts())
            .successCriteria(
                request.getSuccessCriteria() != null
                    ? objectMapper.valueToTree(request.getSuccessCriteria())
                    : null)
            .policy(request.getPolicy())
            .executionMode(request.getExecutionMode())
            .evidenceConfig(request.getEvidenceConfig())
            .runtimeData(new HashMap<>())
            .build();

    // Broadcast execution started
    webSocketHandler.broadcastStatusChange(executionId, "CREATED");

    // Execute the graph asynchronously
    executeGraphAsync(executionId, graph, context);

    // Build response
    GraphExecuteResponse response = new GraphExecuteResponse();
    response.setExecutionId(executionId);
    response.setIterationId(iteration.getIterationId());
    response.setCompiledGraph(buildCompiledGraphInfo(graph));
    response.setStatus("CREATED");
    response.setWsUrl("/ws/debug/patch-loop/" + executionId);
    response.setApiUrl("/api/debug/patch-loop/" + iteration.getIterationId() + "/graph");

    return response;
  }

  /**
   * Gets the state of a graph execution.
   */
  public GraphStateResponse getGraphState(String iterationId) {
    PatchLoopIteration iteration = iterationRepository.findByIterationId(iterationId).orElse(null);
    if (iteration == null) {
      return null;
    }

    String executionId = iteration.getGraphExecutionId();
    if (executionId == null) {
      return null;
    }

    PatchLoopGraphExecution execution = executionRepository.findByExecutionId(executionId).orElse(null);
    if (execution == null) {
      return null;
    }

    GraphStateResponse response = new GraphStateResponse();
    response.setExecutionId(executionId);
    response.setIterationId(iterationId);
    response.setStatus(execution.getExecutionStatus().name());
    response.setCurrentNodeId(execution.getCurrentNodeId());
    response.setAttemptCount(execution.getAttemptCount());
    response.setMermaidSource(execution.getMermaidSource());
    response.setStopReason(execution.getStopReason());
    response.setFinalStatus(execution.getFinalStatus());

    // Load nodes
    List<PatchLoopGraphNode> nodeEntities = nodeRepository.findByExecutionIdOrderByStartedAtAsc(executionId);
    response.setNodes(
        nodeEntities.stream()
            .map(this::mapNodeState)
            .collect(Collectors.toList()));

    // Load attempts
    List<PatchLoopAttempt> attemptEntities = attemptRepository.findByExecutionIdOrderByAttemptNumberAsc(executionId);
    response.setAttempts(
        attemptEntities.stream()
            .map(this::mapAttempt)
            .collect(Collectors.toList()));

    // Load patches
    List<PatchLoopPatchArtifact> patchEntities = patchRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);
    response.setPatches(
        patchEntities.stream()
            .map(this::mapPatch)
            .collect(Collectors.toList()));

    // Build timeline
    GraphStateResponse.ExecutionTimeline timeline = new GraphStateResponse.ExecutionTimeline();
    timeline.setCreatedAt(execution.getCreatedAt());
    timeline.setUpdatedAt(execution.getUpdatedAt());
    timeline.setCompletedAt(execution.getCompletedAt());
    if (execution.getCreatedAt() != null && execution.getCompletedAt() != null) {
      timeline.setTotalDurationMs(
          java.time.Duration.between(execution.getCreatedAt(), execution.getCompletedAt()).toMillis());
    }
    response.setTimeline(timeline);

    // Determine available actions
    List<String> actions = new ArrayList<>();
    if (execution.getExecutionStatus() == GraphExecutionStatus.RUNNING) {
      actions.add("PAUSE");
      actions.add("CANCEL");
    } else if (execution.getExecutionStatus() == GraphExecutionStatus.PAUSED) {
      actions.add("RESUME");
      actions.add("CANCEL");
    }
    response.setAvailableActions(actions);

    return response;
  }

  /**
   * Controls a running graph execution.
   */
  @Transactional
  public void controlExecution(String iterationId, GraphControlRequest request)
      throws GraphExecutionException {

    PatchLoopIteration iteration = iterationRepository.findByIterationId(iterationId).orElse(null);
    if (iteration == null) {
      throw new GraphExecutionException("Iteration not found: " + iterationId);
    }

    String executionId = iteration.getGraphExecutionId();
    if (executionId == null) {
      throw new GraphExecutionException("No graph execution for iteration: " + iterationId);
    }

    switch (request.getAction()) {
      case PAUSE -> {
        executor.pauseExecution(executionId);
        webSocketHandler.broadcastStatusChange(executionId, "PAUSED");
      }
      case RESUME -> {
        executor.resumeExecution(executionId);
        webSocketHandler.broadcastStatusChange(executionId, "RUNNING");
      }
      case CANCEL -> {
        executor.cancelExecution(executionId, request.getReason());
        webSocketHandler.broadcastStatusChange(executionId, "CANCELLED");
      }
      case RETRY_FROM_NODE -> {
        String nodeId = request.getNodeId();
        PatchLoopGraphExecution execution = executionRepository.findByExecutionId(executionId)
            .orElseThrow(() -> new GraphExecutionException("Execution not found: " + executionId));

        // Clear node results from this node onward
        clearNodeResults(executionId, nodeId);

        // Reset to RUNNING
        execution.setExecutionStatus(GraphExecutionStatus.RUNNING);
        execution.setCurrentNodeId(nodeId);
        executionRepository.save(execution);

        // Re-execute from node
        executor.retryFromNode(executionId, nodeId);
        webSocketHandler.broadcastStatusChange(executionId, "RETRYING");
      }
      default -> throw new IllegalArgumentException("Unsupported action: " + request.getAction());
    }
  }

  /**
   * Gets artifacts from a graph execution.
   */
  public ArtifactsResponse getArtifacts(String iterationId) {
    PatchLoopIteration iteration = iterationRepository.findByIterationId(iterationId).orElse(null);
    if (iteration == null) {
      return null;
    }

    String executionId = iteration.getGraphExecutionId();
    if (executionId == null) {
      return null;
    }

    ArtifactsResponse response = new ArtifactsResponse();
    response.setExecutionId(executionId);

    // Load patches
    List<PatchLoopPatchArtifact> patches = patchRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);
    response.setPatches(
        patches.stream()
            .map(this::mapPatchDetail)
            .collect(Collectors.toList()));

    // TODO: Load validations, retests, analyses, evidence

    return response;
  }

  /**
   * Gets active graph executions.
   */
  public List<GraphStateResponse> getActiveGraphExecutions() {
    List<PatchLoopGraphExecution> executions = executionRepository.findActiveExecutions();
    return executions.stream()
        .map(exec -> getGraphState(exec.getIterationId()))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  // Helper methods

  private PatchLoopIteration createIteration(GraphExecuteRequest request) {
    // For now, create a new iteration
    // In real implementation, might reuse existing iteration
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId("patchloop_" + UUID.randomUUID().toString().substring(0, 8));
    iteration.setScriptName(request.getScriptName());
    iteration.setPrompt(request.getGoalPrompt());
    iteration.setContext(request.getContext());
    iteration.setEvidenceConfig(
        request.getEvidenceConfig() != null
            ? objectMapper.valueToTree(request.getEvidenceConfig())
            : null);
    iteration.setExecutionMode(request.getExecutionMode());
    iteration.setMaxAttempts(request.getMaxAttempts());
    iterationRepository.save(iteration);
    return iteration;
  }

  private void executeGraphAsync(String executionId, LoopGraph graph, GraphExecutionContext context) {
    // In real implementation, would use @Async or a message queue
    // For now, execute synchronously
    try {
      executor.executeGraph(executionId, graph, context);
    } catch (GraphExecutionException e) {
      logger.error("Async graph execution failed: {}", executionId, e);
      webSocketHandler.broadcastIterationFailed(executionId, e.getMessage());
    }
  }

  private void clearNodeResults(String executionId, String fromNodeId) {
    // Delete node states from the specified node onwards
    List<PatchLoopGraphNode> nodes = nodeRepository.findByExecutionIdOrderByStartedAtAsc(executionId);
    for (PatchLoopGraphNode node : nodes) {
      if (shouldClearNode(node.getNodeId(), fromNodeId)) {
        nodeRepository.delete(node);
      }
    }
  }

  private boolean shouldClearNode(String nodeId, String fromNodeId) {
    // Simple implementation: clear all nodes
    // In real implementation, would traverse graph to determine which nodes to clear
    return true;
  }

  private JsonNode serializeGraph(LoopGraph graph) {
    try {
      return objectMapper.valueToTree(graph);
    } catch (Exception e) {
      logger.error("Failed to serialize graph", e);
      return JsonNodeFactory.instance.objectNode();
    }
  }

  private JsonNode serializePolicy(GraphExecuteRequest.PatchPolicyConfig policy) {
    if (policy == null) {
      return null;
    }
    try {
      return objectMapper.valueToTree(policy);
    } catch (Exception e) {
      logger.error("Failed to serialize policy", e);
      return null;
    }
  }

  private GraphExecuteResponse.CompiledGraphInfo buildCompiledGraphInfo(LoopGraph graph) {
    GraphExecuteResponse.CompiledGraphInfo info = new GraphExecuteResponse.CompiledGraphInfo();
    info.setNodeCount(graph.getNodes().size());
    info.setEdgeCount(
        graph.getEdges().values().stream().mapToInt(List::size).sum());
    info.setEntryNodeId(graph.getEntryNodeId());
    info.setTerminalNodeIds(new ArrayList<>(graph.getTerminalNodeIds()));

    List<GraphExecuteResponse.GraphNodeInfo> nodes = new ArrayList<>();
    for (GraphNode node : graph.getNodes().values()) {
      GraphExecuteResponse.GraphNodeInfo nodeInfo = new GraphExecuteResponse.GraphNodeInfo();
      nodeInfo.setId(node.id());
      nodeInfo.setType(node.type().name());
      nodeInfo.setLabel(node.label());
      nodeInfo.setConfig(objectMapper.valueToTree(node.config()));
      nodes.add(nodeInfo);
    }
    info.setNodes(nodes);

    return info;
  }

  private GraphStateResponse.NodeStateInfo mapNodeState(PatchLoopGraphNode entity) {
    GraphStateResponse.NodeStateInfo info = new GraphStateResponse.NodeStateInfo();
    info.setNodeId(entity.getNodeId());
    info.setNodeType(entity.getNodeType().name());
    info.setStatus(entity.getStatus().name());
    info.setRetryCount(entity.getRetryCount());
    info.setStartedAt(entity.getStartedAt());
    info.setCompletedAt(entity.getCompletedAt());
    info.setDurationMs(entity.getDurationMs());
    info.setErrorMessage(entity.getErrorMessage());
    info.setOutputSummary(entity.getOutputSummary());
    info.setAttemptNumber(entity.getAttemptNumber());
    return info;
  }

  private GraphStateResponse.AttemptInfo mapAttempt(PatchLoopAttempt entity) {
    GraphStateResponse.AttemptInfo info = new GraphStateResponse.AttemptInfo();
    info.setAttemptNumber(entity.getAttemptNumber());
    info.setRunSessionId(entity.getRunSessionId());
    info.setRunRecordingId(entity.getRunRecordingId());
    info.setStartedAt(entity.getStartedAt());
    info.setCompletedAt(entity.getCompletedAt());
    info.setOutcome(entity.getOutcome() != null ? entity.getOutcome().name() : null);
    info.setStopReason(entity.getStopReason());
    info.setDurationMs(entity.getDurationMs());
    return info;
  }

  private GraphStateResponse.PatchArtifactInfo mapPatch(PatchLoopPatchArtifact entity) {
    GraphStateResponse.PatchArtifactInfo info = new GraphStateResponse.PatchArtifactInfo();
    info.setPatchId(entity.getPatchId());
    info.setAttemptNumber(entity.getAttemptNumber());
    info.setFilesChanged(entity.getFilesChanged());
    info.setLinesChanged(entity.getLinesChanged());
    info.setConfidence(entity.getConfidence());
    info.setApplyStatus(entity.getApplyStatus().name());
    info.setValidationPassed(entity.getValidationPassed());
    info.setRolledBack(entity.getRolledBack());
    info.setCreatedAt(entity.getCreatedAt());
    info.setRationale(entity.getRationale());
    return info;
  }

  private ArtifactsResponse.PatchArtifactDetail mapPatchDetail(PatchLoopPatchArtifact entity) {
    ArtifactsResponse.PatchArtifactDetail detail = new ArtifactsResponse.PatchArtifactDetail();
    detail.setPatchId(entity.getPatchId());
    detail.setAttemptNumber(entity.getAttemptNumber());
    detail.setDiffText(entity.getDiffText());
    detail.setRationale(entity.getRationale());

    // Extract affected files from JSON
    List<String> affectedFiles = new ArrayList<>();
    if (entity.getAffectedFiles() != null && entity.getAffectedFiles().has("files")) {
      JsonNode filesArray = entity.getAffectedFiles().get("files");
      for (JsonNode file : filesArray) {
        affectedFiles.add(file.asText());
      }
    }
    detail.setAffectedFiles(affectedFiles);

    detail.setFilesChanged(entity.getFilesChanged());
    detail.setLinesChanged(entity.getLinesChanged());
    detail.setConfidence(entity.getConfidence());
    detail.setApplyStatus(entity.getApplyStatus().name());
    detail.setGitBranch(entity.getGitBranch());
    detail.setGitCommit(entity.getGitCommit());
    detail.setValidationPassed(entity.getValidationPassed());
    detail.setValidationOutput(entity.getValidationOutput());
    detail.setRolledBack(entity.getRolledBack());
    detail.setRollbackReason(entity.getRollbackReason());
    detail.setCreatedAt(formatDateTime(entity.getCreatedAt()));
    detail.setAppliedAt(formatDateTime(entity.getAppliedAt()));
    return detail;
  }

  private String formatDateTime(LocalDateTime dt) {
    return dt != null ? dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
  }
}
