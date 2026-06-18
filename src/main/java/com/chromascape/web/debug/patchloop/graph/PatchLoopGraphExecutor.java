package com.chromascape.web.debug.patchloop.graph;

import com.chromascape.web.debug.patchloop.dto.AnalysisV1EvidenceBundle;
import com.chromascape.web.debug.patchloop.dto.AnalysisV1Response.DiagnosisSection;
import com.chromascape.web.debug.patchloop.dto.EvidenceConfigDto;
import com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest;
import com.chromascape.web.debug.patchloop.dto.PatchProposal;
import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.service.ExecutionPauseService;
import com.chromascape.web.debug.patchloop.service.ExecutionRuntimeSnapshotService;
import com.chromascape.web.debug.patchloop.service.GraphExecutionContext;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutorRegistry;
import com.chromascape.web.debug.patchloop.service.RetestOrchestrator;
import com.chromascape.web.debug.patchloop.websocket.PatchLoopWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executor for Mermaid-defined patch loop graphs.
 *
 * <p>Executes a compiled {@link LoopGraph} by traversing nodes according to edge conditions.
 * Persists state after each node for crash recovery and emits structured events.
 */
@Component
public class PatchLoopGraphExecutor {

  private static final Logger logger = LoggerFactory.getLogger(PatchLoopGraphExecutor.class);

  private final PatchLoopGraphExecutionRepository executionRepository;
  private final PatchLoopGraphNodeRepository nodeRepository;
  private final PatchLoopAttemptRepository attemptRepository;
  private final PatchLoopIterationRepository iterationRepository;
  private final GraphNodeExecutorRegistry executorRegistry;
  private final ObjectMapper objectMapper;
  private final ExecutionRuntimeSnapshotService snapshotService;
  private final ExecutionPauseService pauseService;
  private final PatchLoopWebSocketHandler webSocketHandler;

  public PatchLoopGraphExecutor(
      PatchLoopGraphExecutionRepository executionRepository,
      PatchLoopGraphNodeRepository nodeRepository,
      PatchLoopAttemptRepository attemptRepository,
      PatchLoopIterationRepository iterationRepository,
      GraphNodeExecutorRegistry executorRegistry,
      ObjectMapper objectMapper,
      ExecutionRuntimeSnapshotService snapshotService,
      ExecutionPauseService pauseService,
      PatchLoopWebSocketHandler webSocketHandler) {
    this.executionRepository = executionRepository;
    this.nodeRepository = nodeRepository;
    this.attemptRepository = attemptRepository;
    this.iterationRepository = iterationRepository;
    this.executorRegistry = executorRegistry;
    this.objectMapper = objectMapper;
    this.snapshotService = snapshotService;
    this.pauseService = pauseService;
    this.webSocketHandler = webSocketHandler;
  }

  /**
   * Executes a graph with the given context.
   *
   * @param executionId the unique execution ID
   * @param graph the compiled graph to execute
   * @param context the execution context
   * @throws GraphExecutionException if execution fails
   */
  @Transactional
  public void executeGraph(String executionId, LoopGraph graph, GraphExecutionContext context)
      throws GraphExecutionException {

    logger.info("Starting graph execution: {}", executionId);

    PatchLoopGraphExecution execution =
        executionRepository
            .findByExecutionId(executionId)
            .orElseThrow(() -> new GraphExecutionException("Execution not found: " + executionId));

    AtomicBoolean cancelled = new AtomicBoolean(false);

    try {
      execution.transitionTo(GraphExecutionStatus.RUNNING);
      executionRepository.save(execution);

      // Start from the entry node
      String currentNodeId = graph.getEntryNodeId();
      int attemptNumber = 1;

      // Create the first attempt
      PatchLoopAttempt attempt = createAttempt(executionId, attemptNumber);
      attemptRepository.save(attempt);

      while (currentNodeId != null && !cancelled.get()) {
        // Check for pause from shared pause service
        if (pauseService.isPaused(execution.getExecutionId())) {
          logger.info("Execution paused at node: {}", currentNodeId);
          execution.transitionTo(GraphExecutionStatus.PAUSED);
          executionRepository.save(execution);
          break;
        }

        // Check if we've hit a terminal node
        if (graph.isTerminalNode(currentNodeId)) {
          logger.info("Reached terminal node: {}", currentNodeId);
          execution.transitionTo(GraphExecutionStatus.COMPLETED);
          execution.setFinalStatus("SUCCESS");
          execution.setStopReason("Reached terminal node");
          execution.setCompletedAt(LocalDateTime.now());
          executionRepository.save(execution);
          // Clear pause flag on terminal transition
          pauseService.clearPaused(execution.getExecutionId());
          break;
        }

        // Get the node to execute
        GraphNode graphNode = graph.getNode(currentNodeId);
        if (graphNode == null) {
          throw new GraphExecutionException("Node not found: " + currentNodeId);
        }

        // Execute the node
        NodeExecutionResult result =
            executeNode(execution, graph, graphNode, context, attemptNumber);

        // Check for cancellation during execution
        execution = executionRepository.findById(execution.getId()).orElseThrow();
        if (execution.getExecutionStatus() == GraphExecutionStatus.CANCELLED) {
          cancelled.set(true);
          break;
        }

        // Determine the next node based on the result
        currentNodeId = determineNextNode(graph, currentNodeId, result);

        // Update current node pointer
        execution.setCurrentNode(currentNodeId);
        executionRepository.save(execution);

        // Check if we should continue looping (e.g., evaluate_success says retry)
        if (currentNodeId != null && currentNodeId.equals(graph.getEntryNodeId())) {
          // Starting a new attempt
          attempt.markCompleted(AttemptOutcome.FAILURE, "Looping back for retry");
          attemptRepository.save(attempt);

          if (attemptNumber >= (context.getMaxAttempts() != null ? context.getMaxAttempts() : 3)) {
            logger.info("Max attempts reached: {}", attemptNumber);
            execution.transitionTo(GraphExecutionStatus.COMPLETED);
            execution.setFinalStatus("MAX_ATTEMPTS_REACHED");
            execution.setStopReason("Maximum attempts exceeded");
            execution.setCompletedAt(LocalDateTime.now());
            executionRepository.save(execution);
            // Clear pause flag on terminal transition
            pauseService.clearPaused(execution.getExecutionId());
            break;
          }

          attemptNumber++;
          execution.incrementAttempt();
          attempt = createAttempt(executionId, attemptNumber);
          attemptRepository.save(attempt);
        }
      }

    } catch (Exception e) {
      logger.error("Graph execution failed: {}", executionId, e);
      execution.transitionTo(GraphExecutionStatus.FAILED);
      execution.setStopReason(e.getMessage());
      execution.setCompletedAt(LocalDateTime.now());
      executionRepository.save(execution);
      // Clear pause flag on terminal transition
      pauseService.clearPaused(execution.getExecutionId());
      throw new GraphExecutionException("Graph execution failed", e);
    }
  }

  /** Executes a single graph node. */
  private NodeExecutionResult executeNode(
      PatchLoopGraphExecution execution,
      LoopGraph graph,
      GraphNode graphNode,
      GraphExecutionContext context,
      int attemptNumber) {

    logger.debug("Executing node: {} ({})", graphNode.id(), graphNode.type());

    // Create or update node state
    PatchLoopGraphNode nodeState =
        getOrCreateNodeState(execution.getExecutionId(), graphNode, attemptNumber);
    nodeState.markStarted();
    nodeRepository.save(nodeState);

    // Broadcast node started event
    webSocketHandler.broadcastNodeStarted(
        execution.getExecutionId(), graphNode.id(), graphNode.type().name());

    try {
      // Get the executor for this node type
      GraphNodeExecutor executor = executorRegistry.getExecutor(graphNode.type());

      if (executor == null) {
        throw new GraphExecutionException("No executor for node type: " + graphNode.type());
      }

      // Execute the node
      GraphNodeExecutor.ExecutionContext execContext =
          new GraphNodeExecutor.ExecutionContext(
              execution.getExecutionId(),
              execution.getIterationId(),
              attemptNumber,
              graphNode.id(),
              graph,
              context);

      long startTime = System.currentTimeMillis();
      NodeExecutionResult result = executor.execute(graphNode, execContext);
      long duration = System.currentTimeMillis() - startTime;

      if (result.success()) {
        nodeState.markCompleted(
            result.outputSummary() != null ? result.outputSummary() : "Completed",
            result.outputData());
        nodeRepository.save(nodeState);
        webSocketHandler.broadcastNodeCompleted(
            execution.getExecutionId(), graphNode.id(), duration, result.outputSummary());
      } else if (result.timedOut()) {
        String timeoutMessage =
            result.outputSummary() != null ? result.outputSummary() : "Execution timed out";
        nodeState.markTimeout(timeoutMessage, result.outputData());
        nodeRepository.save(nodeState);
        webSocketHandler.broadcastNodeFailed(
            execution.getExecutionId(),
            graphNode.id(),
            timeoutMessage,
            nodeState.getRetryCount() != null ? nodeState.getRetryCount() : 0);
      } else {
        String failureMessage =
            result.outputSummary() != null ? result.outputSummary() : "Execution failed";
        nodeState.markFailed(failureMessage);
        nodeState.setOutputData(result.outputData());
        nodeRepository.save(nodeState);
        webSocketHandler.broadcastNodeFailed(
            execution.getExecutionId(),
            graphNode.id(),
            failureMessage,
            nodeState.getRetryCount() != null ? nodeState.getRetryCount() : 0);
      }

      // Save runtime snapshot after node completes (for resume/retry)
      snapshotService.saveSnapshot(execution.getExecutionId(), context.getRuntimeData(), context);

      return result;

    } catch (Exception e) {
      logger.error("Node execution failed: {} ({})", graphNode.id(), graphNode.type(), e);
      nodeState.markFailed(e.getMessage());
      nodeRepository.save(nodeState);

      // Broadcast node failed event
      webSocketHandler.broadcastNodeFailed(
          execution.getExecutionId(),
          graphNode.id(),
          e.getMessage(),
          nodeState.getRetryCount() != null ? nodeState.getRetryCount() : 0);

      return NodeExecutionResult.failure(graphNode.id(), e.getMessage());
    }
  }

  /** Determines the next node to execute based on the current node and execution result. */
  private String determineNextNode(
      LoopGraph graph, String currentNodeId, NodeExecutionResult result) {

    // Get outgoing edges from the current node
    var edges = graph.getOutgoingEdges(currentNodeId);

    if (edges.isEmpty()) {
      return null; // Terminal node
    }

    // Find matching edge based on result
    for (GraphEdge edge : edges) {
      if (shouldFollowEdge(edge, result)) {
        return edge.to();
      }
    }

    // Default to first edge if no match
    logger.warn("No matching edge for result from {}, using first edge", currentNodeId);
    return edges.get(0).to();
  }

  /** Determines if an edge should be followed based on the execution result. */
  private boolean shouldFollowEdge(GraphEdge edge, NodeExecutionResult result) {
    return switch (edge.condition()) {
      case SUCCESS -> result.success();
      case PARTIAL_SUCCESS -> result.success(); // PARTIAL_SUCCESS continues like SUCCESS
      case FAILURE -> !result.success();
      case RETRY -> result.success() == false && result.retry();
      case TIMEOUT -> result.timedOut();
      case ALWAYS -> true;
      case CONDITIONAL -> evaluateConditionalEdge(edge, result);
    };
  }

  /** Evaluates a conditional edge based on result data. */
  private boolean evaluateConditionalEdge(GraphEdge edge, NodeExecutionResult result) {
    // Check if the result has output data with a 'success' field
    if (result.outputData() != null && result.outputData().has("success")) {
      boolean success = result.outputData().path("success").asBoolean(false);
      // For CONDITIONAL edges, check the success value
      return success;
    }

    // Check other common boolean fields
    if (result.outputData() != null) {
      // Check for 'shouldContinue' field (used by evaluate_success)
      if (result.outputData().has("shouldContinue")) {
        return result.outputData().path("shouldContinue").asBoolean(false);
      }
      // Check for 'passed' field (used by validation nodes)
      if (result.outputData().has("passed")) {
        return result.outputData().path("passed").asBoolean(false);
      }
    }

    // Default to false for conditional edges with no matching data
    logger.debug("Conditional edge from {}: no matching boolean field found", edge.from());
    return false;
  }

  /** Gets or creates a node state entity. */
  private PatchLoopGraphNode getOrCreateNodeState(
      String executionId, GraphNode graphNode, int attemptNumber) {

    PatchLoopGraphNode existing =
        nodeRepository.findByExecutionIdAndNodeId(executionId, graphNode.id());

    if (existing != null && existing.getAttemptNumber().equals(attemptNumber)) {
      return existing;
    }

    PatchLoopGraphNode nodeState = new PatchLoopGraphNode();
    nodeState.setExecutionId(executionId);
    nodeState.setNodeId(graphNode.id());
    nodeState.setNodeType(graphNode.type());
    nodeState.setAttemptNumber(attemptNumber);
    return nodeState;
  }

  /** Creates a new attempt record. */
  private PatchLoopAttempt createAttempt(String executionId, int attemptNumber) {
    PatchLoopAttempt attempt = new PatchLoopAttempt();
    attempt.setExecutionId(executionId);
    attempt.setAttemptNumber(attemptNumber);
    attempt.setStartedAt(LocalDateTime.now());
    return attempt;
  }

  /** Pauses an active execution. */
  @Transactional
  public void pauseExecution(String executionId) throws GraphExecutionException {
    PatchLoopGraphExecution execution =
        executionRepository
            .findByExecutionId(executionId)
            .orElseThrow(() -> new GraphExecutionException("Execution not found: " + executionId));

    if (execution.getExecutionStatus() != GraphExecutionStatus.RUNNING) {
      throw new GraphExecutionException(
          "Cannot pause execution in state: " + execution.getExecutionStatus());
    }

    // Set the shared pause flag for the executor loop to check
    pauseService.setPaused(executionId, true);
    execution.transitionTo(GraphExecutionStatus.PAUSED);
    executionRepository.save(execution);
  }

  /** Resumes a paused execution. */
  @Transactional
  public void resumeExecution(String executionId) throws GraphExecutionException {
    PatchLoopGraphExecution execution =
        executionRepository
            .findByExecutionId(executionId)
            .orElseThrow(() -> new GraphExecutionException("Execution not found: " + executionId));

    if (execution.getExecutionStatus() != GraphExecutionStatus.PAUSED) {
      throw new GraphExecutionException(
          "Cannot resume execution in state: " + execution.getExecutionStatus());
    }

    // Clear the shared pause flag
    pauseService.setPaused(executionId, false);

    // Load the graph from JSON
    LoopGraph graph = loadGraph(execution.getGraphJson());
    if (graph == null) {
      throw new GraphExecutionException("Failed to load graph for execution: " + executionId);
    }

    // Rebuild the context from persisted data
    GraphExecutionContext context = rebuildContext(execution);

    execution.transitionTo(GraphExecutionStatus.RUNNING);
    executionRepository.save(execution);

    // Re-execute from current node
    continueExecution(execution, graph, context);
  }

  /** Retries execution from a specific node. */
  @Transactional
  public void retryFromNode(String executionId, String nodeId) throws GraphExecutionException {
    PatchLoopGraphExecution execution =
        executionRepository
            .findByExecutionId(executionId)
            .orElseThrow(() -> new GraphExecutionException("Execution not found: " + executionId));

    // Load the graph from JSON
    LoopGraph graph = loadGraph(execution.getGraphJson());
    if (graph == null) {
      throw new GraphExecutionException("Failed to load graph for execution: " + executionId);
    }

    // Rebuild the context from persisted data
    GraphExecutionContext context = rebuildContext(execution);

    // Set current node and ensure RUNNING state
    execution.setCurrentNodeId(nodeId);
    execution.transitionTo(GraphExecutionStatus.RUNNING);
    executionRepository.save(execution);

    // Clear any stale pause state before retry
    pauseService.clearPaused(executionId);

    // Execute from the specified node
    executeFromNode(execution, graph, context, nodeId);
  }

  /** Cancels an active or paused execution. */
  @Transactional
  public void cancelExecution(String executionId, String reason) throws GraphExecutionException {
    PatchLoopGraphExecution execution =
        executionRepository
            .findByExecutionId(executionId)
            .orElseThrow(() -> new GraphExecutionException("Execution not found: " + executionId));

    if (execution.getExecutionStatus().isTerminal()) {
      throw new GraphExecutionException(
          "Cannot cancel execution in terminal state: " + execution.getExecutionStatus());
    }

    execution.transitionTo(GraphExecutionStatus.CANCELLED);
    execution.setStopReason(reason != null ? reason : "Cancelled by user");
    execution.setCompletedAt(LocalDateTime.now());
    executionRepository.save(execution);

    // Clear pause state on cancel
    pauseService.clearPaused(executionId);
  }

  /** Loads a graph from JSON. */
  private LoopGraph loadGraph(com.fasterxml.jackson.databind.JsonNode graphJson) {
    try {
      return objectMapper.treeToValue(graphJson, LoopGraph.class);
    } catch (Exception e) {
      logger.error("Failed to load graph from JSON", e);
      return null;
    }
  }

  /** Rebuilds the execution context from persisted execution data. */
  private GraphExecutionContext rebuildContext(PatchLoopGraphExecution execution)
      throws GraphExecutionException {
    // Load the iteration to get context data
    PatchLoopIteration iteration =
        iterationRepository.findByIterationId(execution.getIterationId()).orElse(null);

    if (iteration == null) {
      throw new GraphExecutionException("Iteration not found: " + execution.getIterationId());
    }

    // Load runtime data and apply typed rehydration for known types
    Map<String, Object> runtimeData = snapshotService.loadRuntimeData(execution.getExecutionId());
    Map<String, Object> typedRuntimeData = new HashMap<>();

    for (Map.Entry<String, Object> entry : runtimeData.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();

      // Use typed rehydration for known value types based on key prefixes
      if (key.startsWith("patch_")) {
        PatchProposal patch =
            snapshotService.getRuntimeValue(execution.getExecutionId(), key, PatchProposal.class);
        if (patch != null) {
          typedRuntimeData.put(key, patch);
        }
      } else if (key.startsWith("analysis_")) {
        DiagnosisSection section =
            snapshotService.getRuntimeValue(
                execution.getExecutionId(), key, DiagnosisSection.class);
        if (section != null) {
          typedRuntimeData.put(key, section);
        }
      } else if (key.startsWith("retest_")) {
        RetestOrchestrator.RetestResult retest =
            snapshotService.getRuntimeValue(
                execution.getExecutionId(), key, RetestOrchestrator.RetestResult.class);
        if (retest != null) {
          typedRuntimeData.put(key, retest);
        }
      } else if (key.startsWith("evidence_")) {
        AnalysisV1EvidenceBundle evidence =
            snapshotService.getRuntimeValue(
                execution.getExecutionId(), key, AnalysisV1EvidenceBundle.class);
        if (evidence != null) {
          typedRuntimeData.put(key, evidence);
        }
      } else if (key.startsWith("baseline_")) {
        // For baseline evidence, rehydrate to appropriate type
        AnalysisV1EvidenceBundle baseline =
            snapshotService.getRuntimeValue(
                execution.getExecutionId(), key, AnalysisV1EvidenceBundle.class);
        if (baseline != null) {
          typedRuntimeData.put(key, baseline);
        }
      } else {
        // For unknown keys, use the raw value
        typedRuntimeData.put(key, value);
      }
    }

    // Build context from persisted data with typed runtime data
    return GraphExecutionContext.builder()
        .executionId(execution.getExecutionId())
        .iterationId(execution.getIterationId())
        .scriptName(iteration.getScriptName())
        .goalPrompt(iteration.getPrompt())
        .context(iteration.getContext())
        .maxAttempts(iteration.getMaxAttempts() != null ? iteration.getMaxAttempts() : 3)
        .successCriteria(iteration.getSuccessCriteria())
        .policy(
            execution.getPolicyJson() != null
                ? objectMapper.convertValue(
                    execution.getPolicyJson(), GraphExecuteRequest.PatchPolicyConfig.class)
                : null)
        .executionMode(
            iteration.getExecutionMode() != null ? iteration.getExecutionMode() : "analysis_only")
        .evidenceConfig(
            iteration.getEvidenceConfig() != null
                ? objectMapper.convertValue(iteration.getEvidenceConfig(), EvidenceConfigDto.class)
                : null)
        .runtimeData(typedRuntimeData)
        .build();
  }

  /** Continues execution from the current node. */
  private void continueExecution(
      PatchLoopGraphExecution execution, LoopGraph graph, GraphExecutionContext context)
      throws GraphExecutionException {

    logger.info("Continuing execution from node: {}", execution.getCurrentNodeId());

    try {
      AtomicBoolean paused = new AtomicBoolean(false);
      AtomicBoolean cancelled = new AtomicBoolean(false);

      // Get current attempt number
      int attemptNumber = execution.getAttemptCount() != null ? execution.getAttemptCount() : 1;

      // Load or create attempt
      PatchLoopAttempt attempt =
          attemptRepository.findByExecutionIdAndAttemptNumber(
              execution.getExecutionId(), attemptNumber);
      if (attempt == null) {
        attempt = createAttempt(execution.getExecutionId(), attemptNumber);
        attemptRepository.save(attempt);
      }

      String currentNodeId = execution.getCurrentNodeId();

      while (currentNodeId != null && !cancelled.get()) {
        // Check for pause from shared pause service
        if (pauseService.isPaused(execution.getExecutionId())) {
          logger.info("Execution paused at node: {}", currentNodeId);
          execution.transitionTo(GraphExecutionStatus.PAUSED);
          executionRepository.save(execution);
          break;
        }

        // Check if we've hit a terminal node
        if (graph.isTerminalNode(currentNodeId)) {
          logger.info("Reached terminal node: {}", currentNodeId);
          execution.transitionTo(GraphExecutionStatus.COMPLETED);
          execution.setFinalStatus("SUCCESS");
          execution.setStopReason("Reached terminal node");
          execution.setCompletedAt(LocalDateTime.now());
          executionRepository.save(execution);
          // Clear pause flag on terminal transition
          pauseService.clearPaused(execution.getExecutionId());
          break;
        }

        // Get the node to execute
        GraphNode graphNode = graph.getNode(currentNodeId);
        if (graphNode == null) {
          throw new GraphExecutionException("Node not found: " + currentNodeId);
        }

        // Execute the node
        NodeExecutionResult result =
            executeNode(execution, graph, graphNode, context, attemptNumber);

        // Check for cancellation during execution
        execution = executionRepository.findById(execution.getId()).orElseThrow();
        if (execution.getExecutionStatus() == GraphExecutionStatus.CANCELLED) {
          cancelled.set(true);
          break;
        }

        // Determine the next node based on the result
        currentNodeId = determineNextNode(graph, currentNodeId, result);

        // Update current node pointer
        execution.setCurrentNode(currentNodeId);
        executionRepository.save(execution);

        // Check if we should continue looping
        if (currentNodeId != null && currentNodeId.equals(graph.getEntryNodeId())) {
          // Starting a new attempt
          attempt.markCompleted(AttemptOutcome.FAILURE, "Looping back for retry");
          attemptRepository.save(attempt);

          if (attemptNumber >= (context.getMaxAttempts() != null ? context.getMaxAttempts() : 3)) {
            logger.info("Max attempts reached: {}", attemptNumber);
            execution.transitionTo(GraphExecutionStatus.COMPLETED);
            execution.setFinalStatus("MAX_ATTEMPTS_REACHED");
            execution.setStopReason("Maximum attempts exceeded");
            execution.setCompletedAt(LocalDateTime.now());
            executionRepository.save(execution);
            // Clear pause flag on terminal transition
            pauseService.clearPaused(execution.getExecutionId());
            break;
          }

          attemptNumber++;
          execution.incrementAttempt();
          attempt = createAttempt(execution.getExecutionId(), attemptNumber);
          attemptRepository.save(attempt);
        }
      }

    } catch (Exception e) {
      logger.error("Graph execution failed: {}", execution.getExecutionId(), e);
      execution.transitionTo(GraphExecutionStatus.FAILED);
      execution.setStopReason(e.getMessage());
      execution.setCompletedAt(LocalDateTime.now());
      executionRepository.save(execution);
      // Clear pause flag on terminal transition
      pauseService.clearPaused(execution.getExecutionId());
      throw new GraphExecutionException("Graph execution failed", e);
    }
  }

  /** Executes from a specific node (for retry). */
  private void executeFromNode(
      PatchLoopGraphExecution execution,
      LoopGraph graph,
      GraphExecutionContext context,
      String startNodeId)
      throws GraphExecutionException {

    logger.info("Executing from node: {}", startNodeId);

    AtomicBoolean cancelled = new AtomicBoolean(false);

    try {
      int attemptNumber = execution.getAttemptCount() != null ? execution.getAttemptCount() : 1;

      // Load or create attempt
      PatchLoopAttempt attempt =
          attemptRepository.findByExecutionIdAndAttemptNumber(
              execution.getExecutionId(), attemptNumber);
      if (attempt == null) {
        attempt = createAttempt(execution.getExecutionId(), attemptNumber);
        attemptRepository.save(attempt);
      }

      String currentNodeId = startNodeId;

      while (currentNodeId != null && !cancelled.get()) {
        // Check for pause from shared pause service
        if (pauseService.isPaused(execution.getExecutionId())) {
          logger.info("Execution paused at node: {}", currentNodeId);
          execution.transitionTo(GraphExecutionStatus.PAUSED);
          executionRepository.save(execution);
          break;
        }

        // Check if we've hit a terminal node
        if (graph.isTerminalNode(currentNodeId)) {
          logger.info("Reached terminal node: {}", currentNodeId);
          execution.transitionTo(GraphExecutionStatus.COMPLETED);
          execution.setFinalStatus("SUCCESS");
          execution.setStopReason("Reached terminal node");
          execution.setCompletedAt(LocalDateTime.now());
          executionRepository.save(execution);
          // Clear pause flag on terminal transition
          pauseService.clearPaused(execution.getExecutionId());
          break;
        }

        // Get the node to execute
        GraphNode graphNode = graph.getNode(currentNodeId);
        if (graphNode == null) {
          throw new GraphExecutionException("Node not found: " + currentNodeId);
        }

        // Execute the node
        NodeExecutionResult result =
            executeNode(execution, graph, graphNode, context, attemptNumber);

        // Check for cancellation during execution
        execution = executionRepository.findById(execution.getId()).orElseThrow();
        if (execution.getExecutionStatus() == GraphExecutionStatus.CANCELLED) {
          cancelled.set(true);
          break;
        }

        // Determine the next node based on the result
        currentNodeId = determineNextNode(graph, currentNodeId, result);

        // Update current node pointer
        execution.setCurrentNode(currentNodeId);
        executionRepository.save(execution);

        // Check if we should continue looping (e.g., evaluate_success says retry)
        if (currentNodeId != null && currentNodeId.equals(graph.getEntryNodeId())) {
          // Starting a new attempt
          attempt.markCompleted(AttemptOutcome.FAILURE, "Looping back for retry");
          attemptRepository.save(attempt);

          if (attemptNumber >= (context.getMaxAttempts() != null ? context.getMaxAttempts() : 3)) {
            logger.info("Max attempts reached: {}", attemptNumber);
            execution.transitionTo(GraphExecutionStatus.COMPLETED);
            execution.setFinalStatus("MAX_ATTEMPTS_REACHED");
            execution.setStopReason("Maximum attempts exceeded");
            execution.setCompletedAt(LocalDateTime.now());
            executionRepository.save(execution);
            // Clear pause flag on terminal transition
            pauseService.clearPaused(execution.getExecutionId());
            break;
          }

          attemptNumber++;
          execution.incrementAttempt();
          attempt = createAttempt(execution.getExecutionId(), attemptNumber);
          attemptRepository.save(attempt);
        }
      }

    } catch (Exception e) {
      logger.error("Graph execution failed: {}", execution.getExecutionId(), e);
      execution.transitionTo(GraphExecutionStatus.FAILED);
      execution.setStopReason(e.getMessage());
      execution.setCompletedAt(LocalDateTime.now());
      executionRepository.save(execution);
      // Clear pause flag on terminal transition
      pauseService.clearPaused(execution.getExecutionId());
      throw new GraphExecutionException("Graph execution failed", e);
    }
  }
}
