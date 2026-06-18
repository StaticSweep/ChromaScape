package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.dto.*;
import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.graph.*;
import com.chromascape.web.debug.patchloop.websocket.PatchLoopWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  private final PatchLoopValidationArtifactRepository validationRepository;
  private final PatchLoopRetestArtifactRepository retestRepository;
  private final PatchLoopAnalysisArtifactRepository analysisArtifactRepository;
  private final PatchLoopEvidenceArtifactRepository evidenceArtifactRepository;
  private final ObjectMapper objectMapper;
  private final PatchLoopAsyncWorker patchLoopAsyncWorker;
  private final ScriptExecutionService scriptExecutionService;
  private final PatchLoopPersistenceService persistenceService;
  private final PatchLoopSessionResolverService sessionResolverService;

  @Autowired
  public PatchLoopGraphOrchestratorService(
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
      PatchLoopValidationArtifactRepository validationRepository,
      PatchLoopRetestArtifactRepository retestRepository,
      PatchLoopAnalysisArtifactRepository analysisArtifactRepository,
      PatchLoopEvidenceArtifactRepository evidenceArtifactRepository,
      ObjectMapper objectMapper,
      PatchLoopAsyncWorker patchLoopAsyncWorker,
      ScriptExecutionService scriptExecutionService,
      PatchLoopPersistenceService persistenceService,
      PatchLoopSessionResolverService sessionResolverService) {
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
    this.validationRepository = validationRepository;
    this.retestRepository = retestRepository;
    this.analysisArtifactRepository = analysisArtifactRepository;
    this.evidenceArtifactRepository = evidenceArtifactRepository;
    this.objectMapper = objectMapper;
    this.patchLoopAsyncWorker = patchLoopAsyncWorker;
    this.scriptExecutionService = scriptExecutionService;
    this.persistenceService = persistenceService;
    this.sessionResolverService = sessionResolverService;
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

    // Create or reuse iteration
    PatchLoopIteration iteration;
    if (request.getIterationId() != null) {
      // Reuse existing iteration
      if (request.getIterationId().isBlank()) {
        throw new IllegalArgumentException("iterationId must not be blank");
      }
      iteration =
          iterationRepository
              .findByIterationId(request.getIterationId())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Iteration not found: " + request.getIterationId()));
      logger.info("Reusing existing iteration: {}", iteration.getIterationId());
    } else {
      // Create new iteration
      iteration = createIteration(request);
    }

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
    iteration.setExecutionMode(request.getNormalizedExecutionMode());
    iteration.setMaxAttempts(request.getMaxAttempts());
    iteration.setSuccessCriteria(
        request.getSuccessCriteria() != null
            ? objectMapper.valueToTree(request.getSuccessCriteria())
            : null);
    if (request.getSessionContext() != null) {
      iteration.setSessionContext(objectMapper.valueToTree(request.getSessionContext()));
    }
    var resolvedSession = sessionResolverService.resolveForIteration(iteration);
    if (resolvedSession.isPresent()) {
      sessionResolverService.applyResolvedSession(iteration, resolvedSession.get());
    }
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
            .executionMode(request.getNormalizedExecutionMode())
            .evidenceConfig(request.getEvidenceConfig())
            .runtimeData(new HashMap<>())
            .taskMetadata(request.getTaskMetadata())
            .build();

    // Broadcast execution started
    webSocketHandler.broadcastStatusChange(executionId, "CREATED");

    scheduleAsyncGraphExecution(executionId, graph, context);

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
   * Compiles a Mermaid graph without executing it.
   *
   * <p>Validates the Mermaid syntax and returns the compiled graph structure.
   *
   * @param mermaid the Mermaid graph source
   * @return the compiled graph info
   * @throws GraphCompilationException if compilation fails
   */
  public GraphExecuteResponse.CompiledGraphInfo compileGraph(String mermaid)
      throws GraphCompilationException {
    logger.debug("Compiling Mermaid graph");

    LoopGraph graph = compiler.compile(mermaid);
    return buildCompiledGraphInfo(graph);
  }

  /** Gets the state of a graph execution. */
  public GraphStateResponse getGraphState(String iterationId) {
    PatchLoopIteration iteration = iterationRepository.findByIterationId(iterationId).orElse(null);
    if (iteration == null) {
      return null;
    }

    String executionId = iteration.getGraphExecutionId();
    if (executionId == null) {
      return null;
    }

    PatchLoopGraphExecution execution =
        executionRepository.findByExecutionId(executionId).orElse(null);
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
    List<PatchLoopGraphNode> nodeEntities =
        nodeRepository.findByExecutionIdOrderByStartedAtAsc(executionId);
    response.setNodes(nodeEntities.stream().map(this::mapNodeState).collect(Collectors.toList()));

    // Load attempts
    List<PatchLoopAttempt> attemptEntities =
        attemptRepository.findByExecutionIdOrderByAttemptNumberAsc(executionId);
    response.setAttempts(
        attemptEntities.stream().map(this::mapAttempt).collect(Collectors.toList()));

    // Load patches
    List<PatchLoopPatchArtifact> patchEntities =
        patchRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);
    response.setPatches(patchEntities.stream().map(this::mapPatch).collect(Collectors.toList()));

    // Build timeline
    GraphStateResponse.ExecutionTimeline timeline = new GraphStateResponse.ExecutionTimeline();
    timeline.setCreatedAt(execution.getCreatedAt());
    timeline.setUpdatedAt(execution.getUpdatedAt());
    timeline.setCompletedAt(execution.getCompletedAt());
    if (execution.getCreatedAt() != null && execution.getCompletedAt() != null) {
      timeline.setTotalDurationMs(
          java.time.Duration.between(execution.getCreatedAt(), execution.getCompletedAt())
              .toMillis());
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
    } else if (execution.getExecutionStatus() == GraphExecutionStatus.CREATED) {
      actions.add("RELAUNCH_IF_CREATED");
      actions.add("CANCEL");
    }
    response.setAvailableActions(actions);

    return response;
  }

  /** Controls a running graph execution. */
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
        PatchLoopGraphExecution execution =
            executionRepository
                .findByExecutionId(executionId)
                .orElseThrow(
                    () -> new GraphExecutionException("Execution not found: " + executionId));

        if (execution.getExecutionStatus() != GraphExecutionStatus.RUNNING) {
          throw new GraphExecutionException(
              "Cannot pause execution in state: "
                  + execution.getExecutionStatus()
                  + ". PAUSE only allowed from RUNNING state.");
        }

        executor.pauseExecution(executionId);
        scriptExecutionService.pauseScript(executionId);
        webSocketHandler.broadcastStatusChange(executionId, "PAUSED");
      }
      case RESUME -> {
        PatchLoopGraphExecution execution =
            executionRepository
                .findByExecutionId(executionId)
                .orElseThrow(
                    () -> new GraphExecutionException("Execution not found: " + executionId));

        if (execution.getExecutionStatus() != GraphExecutionStatus.PAUSED) {
          throw new GraphExecutionException(
              "Cannot resume execution in state: "
                  + execution.getExecutionStatus()
                  + ". RESUME only allowed from PAUSED state.");
        }

        // Use async worker to avoid blocking the HTTP response
        scriptExecutionService.resumeScript(executionId);
        patchLoopAsyncWorker.resumeExecutionAsync(executionId);
        webSocketHandler.broadcastStatusChange(executionId, "RUNNING");
      }
      case CANCEL -> {
        PatchLoopGraphExecution execution =
            executionRepository
                .findByExecutionId(executionId)
                .orElseThrow(
                    () -> new GraphExecutionException("Execution not found: " + executionId));

        if (execution.getExecutionStatus().isTerminal()) {
          throw new GraphExecutionException(
              "Cannot cancel execution in terminal state: " + execution.getExecutionStatus());
        }

        executor.cancelExecution(executionId, request.getReason());
        scriptExecutionService.stopScript(executionId, request.getReason());
        webSocketHandler.broadcastStatusChange(executionId, "CANCELLED");
      }
      case RETRY_FROM_NODE -> {
        String nodeId = request.getRetryFromNodeId();
        if (nodeId == null || nodeId.isBlank()) {
          throw new IllegalArgumentException("retryFromNodeId required for RETRY_FROM_NODE action");
        }

        PatchLoopGraphExecution execution =
            executionRepository
                .findByExecutionId(executionId)
                .orElseThrow(
                    () -> new GraphExecutionException("Execution not found: " + executionId));

        // Only allow retry from PAUSED or terminal states (COMPLETED, FAILED, CANCELLED)
        GraphExecutionStatus status = execution.getExecutionStatus();
        if (status == GraphExecutionStatus.RUNNING || status == GraphExecutionStatus.CREATED) {
          throw new GraphExecutionException(
              "Cannot retry from node in state: "
                  + status
                  + ". RETRY_FROM_NODE only allowed from PAUSED or terminal states.");
        }

        // Clear node results from this node onward
        clearNodeResults(executionId, nodeId);

        // Reset to RUNNING
        execution.setExecutionStatus(GraphExecutionStatus.RUNNING);
        execution.setCurrentNodeId(nodeId);
        executionRepository.save(execution);

        // Use async worker to avoid blocking the HTTP response
        patchLoopAsyncWorker.retryFromNodeAsync(executionId, nodeId);
        webSocketHandler.broadcastStatusChange(executionId, "RETRYING");
      }
      case RELAUNCH_IF_CREATED -> {
        PatchLoopGraphExecution execution =
            executionRepository
                .findByExecutionId(executionId)
                .orElseThrow(
                    () -> new GraphExecutionException("Execution not found: " + executionId));

        if (execution.getExecutionStatus() != GraphExecutionStatus.CREATED) {
          throw new GraphExecutionException(
              "Cannot relaunch execution in state: "
                  + execution.getExecutionStatus()
                  + ". RELAUNCH_IF_CREATED only allowed from CREATED state.");
        }

        // Relaunch is only safe before any node execution has begun.
        List<PatchLoopGraphNode> nodes =
            nodeRepository.findByExecutionIdOrderByStartedAtAsc(executionId);
        if (!nodes.isEmpty()) {
          throw new GraphExecutionException(
              "Cannot relaunch execution once nodes have started. Use RESUME/CANCEL instead.");
        }

        LoopGraph graph = loadGraph(execution.getGraphJson());
        if (graph == null) {
          throw new GraphExecutionException("Graph is null for execution: " + executionId);
        }

        GraphExecutionContext context = buildExecutionContext(iteration, execution);
        scheduleAsyncGraphExecution(executionId, graph, context);
        webSocketHandler.broadcastStatusChange(executionId, "RELAUNCHING_CREATED");
      }
      default -> throw new IllegalArgumentException("Unsupported action: " + request.getAction());
    }
  }

  /**
   * Gets artifacts from a graph execution.
   *
   * <p>Loads all artifact types including patches, validations, retests, analyses, and evidence.
   *
   * @param iterationId the iteration ID
   * @return the artifacts response, or null if iteration not found
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
    List<PatchLoopPatchArtifact> patches =
        patchRepository.findByExecutionIdOrderByCreatedAtAsc(executionId);
    response.setPatches(patches.stream().map(this::mapPatchDetail).collect(Collectors.toList()));

    // Load validations
    List<PatchLoopValidationArtifact> validations =
        validationRepository.findByExecutionIdOrderByTimestampAsc(executionId);
    response.setValidations(
        validations.stream().map(this::mapValidationArtifact).collect(Collectors.toList()));

    // Load retests
    List<PatchLoopRetestArtifact> retests =
        retestRepository.findByExecutionIdOrderByTimestampAsc(executionId);
    response.setRetests(retests.stream().map(this::mapRetestArtifact).collect(Collectors.toList()));

    // Load analyses
    List<PatchLoopAnalysisArtifact> analyses =
        analysisArtifactRepository.findByExecutionIdOrderByTimestampAsc(executionId);
    response.setAnalyses(
        analyses.stream().map(this::mapAnalysisArtifact).collect(Collectors.toList()));

    // Load evidence
    List<PatchLoopEvidenceArtifact> evidence =
        evidenceArtifactRepository.findByExecutionIdOrderByTimestampAsc(executionId);
    response.setEvidence(
        evidence.stream().map(this::mapEvidenceArtifact).collect(Collectors.toList()));

    // Load script run terminal details from node execution state
    List<PatchLoopGraphNode> graphNodes =
        nodeRepository.findByExecutionIdOrderByStartedAtAsc(executionId);
    response.setScriptRuns(
        graphNodes.stream()
            .filter(node -> node.getNodeType() == NodeType.RUN_SCRIPT)
            .map(this::mapScriptRunArtifact)
            .collect(Collectors.toList()));

    return response;
  }

  /** Gets active graph executions. */
  public List<GraphStateResponse> getActiveGraphExecutions() {
    List<PatchLoopGraphExecution> executions = executionRepository.findActiveExecutions();
    return executions.stream()
        .map(exec -> getGraphState(exec.getIterationId()))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  // Helper methods

  private void scheduleAsyncGraphExecution(
      String executionId, LoopGraph graph, GraphExecutionContext context) {
    // Schedule async execution AFTER the transaction commits to avoid the race condition
    // where the async thread starts before the DB records are visible.
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              launchAsyncGraphExecution(executionId, graph, context);
            }
          });
      return;
    }

    // No active transaction (e.g. test or non-transactional caller) — launch immediately
    launchAsyncGraphExecution(executionId, graph, context);
  }

  /**
   * Launches async graph execution, persisting terminal failure if submission fails.
   *
   * <p>This method runs outside any transaction (afterCommit callback or immediate). If the async
   * submission itself throws, the graph execution and iteration are marked as FAILED so they don't
   * remain stuck in CREATED.
   */
  private void launchAsyncGraphExecution(
      String executionId, LoopGraph graph, GraphExecutionContext context) {
    try {
      patchLoopAsyncWorker.executeGraphAsync(executionId, graph, context);
    } catch (Exception e) {
      logger.error("Failed to submit async graph execution {}: {}", executionId, e.getMessage(), e);
      try {
        persistenceService.markGraphExecutionFailed(
            executionId,
            PatchLoopFailureCode.INTERNAL_ERROR,
            "Async submission failed: " + e.getMessage());
      } catch (Exception persistEx) {
        logger.error(
            "Failed to persist terminal failure for execution {}: {}",
            executionId,
            persistEx.getMessage());
      }
    }
  }

  private GraphExecutionContext buildExecutionContext(
      PatchLoopIteration iteration, PatchLoopGraphExecution execution) {
    return GraphExecutionContext.builder()
        .executionId(execution.getExecutionId())
        .iterationId(iteration.getIterationId())
        .scriptName(iteration.getScriptName())
        .goalPrompt(iteration.getPrompt())
        .context(iteration.getContext())
        .maxAttempts(iteration.getMaxAttempts())
        .successCriteria(iteration.getSuccessCriteria())
        .policy(
            execution.getPolicyJson() != null
                ? objectMapper.convertValue(
                    execution.getPolicyJson(), GraphExecuteRequest.PatchPolicyConfig.class)
                : null)
        .executionMode(iteration.getExecutionMode())
        .evidenceConfig(
            iteration.getEvidenceConfig() != null
                ? objectMapper.convertValue(iteration.getEvidenceConfig(), EvidenceConfigDto.class)
                : null)
        .runtimeData(new HashMap<>())
        .taskMetadata(new HashMap<>())
        .build();
  }

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
    iteration.setExecutionMode(request.getNormalizedExecutionMode());
    iteration.setMaxAttempts(request.getMaxAttempts());
    if (request.getSessionContext() != null) {
      iteration.setSessionContext(objectMapper.valueToTree(request.getSessionContext()));
    }
    var resolvedSession = sessionResolverService.resolveForIteration(iteration);
    if (resolvedSession.isPresent()) {
      sessionResolverService.applyResolvedSession(iteration, resolvedSession.get());
    }
    iterationRepository.save(iteration);
    return iteration;
  }

  /**
   * Clears node results from the specified node onwards using selective downstream pruning.
   *
   * <p>Traverses the graph to find all downstream nodes from the specified node and only clears
   * those, preserving upstream artifacts.
   *
   * @param executionId the execution ID
   * @param fromNodeId the node ID to start clearing from
   * @throws GraphExecutionException if execution not found
   */
  private void clearNodeResults(String executionId, String fromNodeId)
      throws GraphExecutionException {
    PatchLoopGraphExecution execution =
        executionRepository
            .findByExecutionId(executionId)
            .orElseThrow(() -> new GraphExecutionException("Execution not found: " + executionId));

    LoopGraph graph = loadGraph(execution.getGraphJson());
    if (graph == null) {
      logger.warn("Cannot clear node results: graph is null for execution {}", executionId);
      throw new GraphExecutionException("Graph is null for execution: " + executionId);
    }

    Set<String> downstreamNodes = computeDownstreamNodes(graph, fromNodeId);
    downstreamNodes.add(fromNodeId); // Include the node itself for retry

    List<PatchLoopGraphNode> nodes =
        nodeRepository.findByExecutionIdOrderByStartedAtAsc(executionId);
    for (PatchLoopGraphNode node : nodes) {
      if (downstreamNodes.contains(node.getNodeId())) {
        nodeRepository.delete(node);
      }
    }

    logger.debug(
        "Cleared {} nodes (including retry node) from {}", downstreamNodes.size(), fromNodeId);
  }

  /**
   * Computes all downstream nodes from a given starting node.
   *
   * <p>Performs a breadth-first traversal of the graph starting from the outgoing edges of the
   * specified node to find all nodes that would be affected by retrying from it.
   *
   * @param graph the compiled graph
   * @param fromNodeId the starting node ID
   * @return set of all downstream node IDs
   */
  private Set<String> computeDownstreamNodes(LoopGraph graph, String fromNodeId) {
    Set<String> downstream = new HashSet<>();
    Queue<String> queue = new LinkedList<>();

    // Start with all immediate successors of the from node
    queue.addAll(
        graph.getOutgoingEdges(fromNodeId).stream()
            .map(GraphEdge::to)
            .collect(Collectors.toList()));

    // BFS to find all reachable nodes
    while (!queue.isEmpty()) {
      String nodeId = queue.poll();
      if (!downstream.add(nodeId)) {
        continue; // Already visited
      }
      queue.addAll(
          graph.getOutgoingEdges(nodeId).stream().map(GraphEdge::to).collect(Collectors.toList()));
    }

    return downstream;
  }

  /**
   * Loads a graph from persisted JSON.
   *
   * @param graphJson the graph JSON node
   * @return the loaded LoopGraph, or null if loading fails
   */
  private LoopGraph loadGraph(JsonNode graphJson) {
    try {
      return objectMapper.treeToValue(graphJson, LoopGraph.class);
    } catch (Exception e) {
      logger.error("Failed to load graph from JSON", e);
      return null;
    }
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
    info.setEdgeCount(graph.getEdges().values().stream().mapToInt(List::size).sum());
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

  private ArtifactsResponse.ValidationArtifact mapValidationArtifact(
      PatchLoopValidationArtifact entity) {
    ArtifactsResponse.ValidationArtifact artifact = new ArtifactsResponse.ValidationArtifact();
    artifact.setPatchId(entity.getPatchId());
    artifact.setAttemptNumber(entity.getAttemptNumber());
    artifact.setPassed(entity.getPassed());
    artifact.setOutput(entity.getOutput());
    artifact.setExitCode(entity.getExitCode());
    artifact.setDurationMs(entity.getDurationMs());
    artifact.setTimestamp(formatDateTime(entity.getTimestamp()));
    artifact.setRolledBack(entity.getRolledBack());
    artifact.setRollbackReason(entity.getRollbackReason());
    return artifact;
  }

  private ArtifactsResponse.RetestArtifact mapRetestArtifact(PatchLoopRetestArtifact entity) {
    ArtifactsResponse.RetestArtifact artifact = new ArtifactsResponse.RetestArtifact();
    artifact.setAttemptNumber(entity.getAttemptNumber());
    artifact.setBaselineAttemptNumber(entity.getBaselineAttemptNumber());
    artifact.setSuccess(entity.getSuccess());
    artifact.setComparisonSummary(entity.getComparisonSummary());

    // Parse improvements and regressions as numeric counts (stored as string representations of
    // integers)
    if (entity.getImprovements() != null && !entity.getImprovements().isBlank()) {
      try {
        int improvementsCount = Integer.parseInt(entity.getImprovements().trim());
        artifact.setImprovementsCount(improvementsCount);
      } catch (NumberFormatException e) {
        // Fallback to list parsing if it's actually a pipe-delimited string
        artifact.setImprovements(Arrays.asList(entity.getImprovements().split("\\|")));
      }
    }
    if (entity.getRegressions() != null && !entity.getRegressions().isBlank()) {
      try {
        int regressionsCount = Integer.parseInt(entity.getRegressions().trim());
        artifact.setRegressionsCount(regressionsCount);
      } catch (NumberFormatException e) {
        // Fallback to list parsing if it's actually a pipe-delimited string
        artifact.setRegressions(Arrays.asList(entity.getRegressions().split("\\|")));
      }
    }

    artifact.setTimestamp(formatDateTime(entity.getTimestamp()));
    return artifact;
  }

  private ArtifactsResponse.AnalysisArtifact mapAnalysisArtifact(PatchLoopAnalysisArtifact entity) {
    ArtifactsResponse.AnalysisArtifact artifact = new ArtifactsResponse.AnalysisArtifact();
    artifact.setAttemptNumber(entity.getAttemptNumber());
    artifact.setAnalysisId(entity.getAnalysisId());
    artifact.setDiagnosisSummary(entity.getDiagnosisSummary());
    artifact.setRootCause(entity.getRootCause());
    artifact.setConfidence(entity.getConfidence());
    artifact.setRecommendationCount(entity.getRecommendationCount());
    artifact.setTimestamp(formatDateTime(entity.getTimestamp()));
    return artifact;
  }

  private ArtifactsResponse.EvidenceArtifact mapEvidenceArtifact(PatchLoopEvidenceArtifact entity) {
    ArtifactsResponse.EvidenceArtifact artifact = new ArtifactsResponse.EvidenceArtifact();
    artifact.setAttemptNumber(entity.getAttemptNumber());
    artifact.setEvidenceType(entity.getEvidenceType());
    artifact.setCount(entity.getCount());
    artifact.setSummary(entity.getSummary());
    artifact.setTimestamp(formatDateTime(entity.getTimestamp()));
    return artifact;
  }

  private ArtifactsResponse.ScriptRunArtifact mapScriptRunArtifact(PatchLoopGraphNode entity) {
    ArtifactsResponse.ScriptRunArtifact artifact = new ArtifactsResponse.ScriptRunArtifact();
    artifact.setNodeId(entity.getNodeId());
    artifact.setAttemptNumber(entity.getAttemptNumber());
    artifact.setDurationMs(entity.getDurationMs());
    artifact.setStartedAt(formatDateTime(entity.getStartedAt()));
    artifact.setCompletedAt(formatDateTime(entity.getCompletedAt()));

    JsonNode outputData = entity.getOutputData();
    artifact.setScriptName(readText(outputData, "scriptName", null));
    artifact.setBackend(readText(outputData, "backend", "local_instance"));

    boolean timedOutFromData = readBoolean(outputData, "timedOut", false);
    boolean timedOutFromStatus = entity.getStatus() == NodeExecutionStatus.TIMEOUT;
    boolean timedOut = timedOutFromData || timedOutFromStatus;
    artifact.setTimedOut(timedOut);

    String statusFromData = readText(outputData, "terminalStatus", null);
    if (statusFromData != null && !statusFromData.isBlank()) {
      artifact.setTerminalStatus(statusFromData);
    } else if (timedOut) {
      artifact.setTerminalStatus("TIMEOUT");
    } else if (entity.getStatus() == NodeExecutionStatus.COMPLETED) {
      artifact.setTerminalStatus("COMPLETED");
    } else {
      artifact.setTerminalStatus("FAILED");
    }

    boolean success =
        readBoolean(outputData, "success", entity.getStatus() == NodeExecutionStatus.COMPLETED);
    artifact.setSuccess(success);

    String errorFromOutput = readText(outputData, "errorMessage", null);
    if (errorFromOutput != null && !errorFromOutput.isBlank()) {
      artifact.setErrorMessage(errorFromOutput);
    } else if (entity.getErrorMessage() != null && !entity.getErrorMessage().isBlank()) {
      artifact.setErrorMessage(entity.getErrorMessage());
    } else if (!success) {
      artifact.setErrorMessage(entity.getOutputSummary());
    }

    return artifact;
  }

  private String readText(JsonNode node, String key, String defaultValue) {
    if (node == null || !node.has(key) || node.get(key).isNull()) {
      return defaultValue;
    }
    String value = node.get(key).asText(defaultValue);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private boolean readBoolean(JsonNode node, String key, boolean defaultValue) {
    if (node == null || !node.has(key) || node.get(key).isNull()) {
      return defaultValue;
    }
    return node.get(key).asBoolean(defaultValue);
  }
}
