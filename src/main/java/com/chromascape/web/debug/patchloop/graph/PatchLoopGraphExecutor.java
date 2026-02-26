package com.chromascape.web.debug.patchloop.graph;

import com.chromascape.web.debug.patchloop.dto.EvidenceConfigDto;
import com.chromascape.web.debug.patchloop.dto.GraphExecuteRequest;
import com.chromascape.web.debug.patchloop.entity.*;
import com.chromascape.web.debug.patchloop.service.ExecutionRuntimeSnapshotService;
import com.chromascape.web.debug.patchloop.service.GraphExecutionContext;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutorRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
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

  public PatchLoopGraphExecutor(
      PatchLoopGraphExecutionRepository executionRepository,
      PatchLoopGraphNodeRepository nodeRepository,
      PatchLoopAttemptRepository attemptRepository,
      PatchLoopIterationRepository iterationRepository,
      GraphNodeExecutorRegistry executorRegistry,
      ObjectMapper objectMapper,
      ExecutionRuntimeSnapshotService snapshotService) {
    this.executionRepository = executionRepository;
    this.nodeRepository = nodeRepository;
    this.attemptRepository = attemptRepository;
    this.iterationRepository = iterationRepository;
    this.executorRegistry = executorRegistry;
    this.objectMapper = objectMapper;
    this.snapshotService = snapshotService;
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

    AtomicBoolean paused = new AtomicBoolean(false);
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
        // Check for pause
        if (paused.get()) {
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
          executionRepository.save(execution);
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
            executionRepository.save(execution);
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
      executionRepository.save(execution);
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

      NodeExecutionResult result = executor.execute(graphNode, execContext);

      // Update node state with result
      nodeState.markCompleted(
          result.outputSummary() != null ? result.outputSummary() : "Completed",
          result.outputData());
      nodeRepository.save(nodeState);

      // Save runtime snapshot after node completes (for resume/retry)
      snapshotService.saveSnapshot(execution.getExecutionId(), context.getRuntimeData(), context);

      return result;

    } catch (Exception e) {
      logger.error("Node execution failed: {} ({})", graphNode.id(), graphNode.type(), e);
      nodeState.markFailed(e.getMessage());
      nodeRepository.save(nodeState);
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
    executionRepository.save(execution);
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

    // Build context from persisted data
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
        .runtimeData(snapshotService.loadRuntimeData(execution.getExecutionId()))
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
        // Check for pause
        if (paused.get()) {
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

      while (currentNodeId != null) {
        // Check if we've hit a terminal node
        if (graph.isTerminalNode(currentNodeId)) {
          logger.info("Reached terminal node: {}", currentNodeId);
          execution.transitionTo(GraphExecutionStatus.COMPLETED);
          execution.setFinalStatus("SUCCESS");
          execution.setStopReason("Reached terminal node");
          execution.setCompletedAt(LocalDateTime.now());
          executionRepository.save(execution);
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

        // Check for cancellation
        execution = executionRepository.findById(execution.getId()).orElseThrow();
        if (execution.getExecutionStatus() == GraphExecutionStatus.CANCELLED) {
          break;
        }

        // Determine the next node based on the result
        currentNodeId = determineNextNode(graph, currentNodeId, result);

        // Update current node pointer
        execution.setCurrentNode(currentNodeId);
        executionRepository.save(execution);
      }

    } catch (Exception e) {
      logger.error("Graph execution failed: {}", execution.getExecutionId(), e);
      execution.transitionTo(GraphExecutionStatus.FAILED);
      execution.setStopReason(e.getMessage());
      execution.setCompletedAt(LocalDateTime.now());
      executionRepository.save(execution);
      throw new GraphExecutionException("Graph execution failed", e);
    }
  }
}
