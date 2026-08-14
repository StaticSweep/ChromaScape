package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.LoopGraph;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;

/**
 * Interface for executing a graph node.
 *
 * <p>Implementations handle specific node types (RUN_SCRIPT, COLLECT_EVIDENCE, etc.).
 */
public interface GraphNodeExecutor {

  /**
   * Executes a graph node.
   *
   * @param node the node to execute
   * @param context the execution context
   * @return the execution result
   * @throws Exception if execution fails
   */
  NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception;

  /**
   * Returns the node type this executor handles.
   *
   * @return the node type
   */
  NodeType getNodeType();

  /**
   * Execution context for a node.
   *
   * @param executionId the execution ID
   * @param iterationId the iteration ID
   * @param attemptNumber the attempt number
   * @param graph the graph being executed
   * @param context the graph execution context
   */
  record ExecutionContext(
      String executionId,
      String iterationId,
      int attemptNumber,
      String nodeId,
      LoopGraph graph,
      GraphExecutionContext context) {

    public ExecutionContext {
      if (nodeId == null) {
        nodeId = "";
      }
    }
  }
}
