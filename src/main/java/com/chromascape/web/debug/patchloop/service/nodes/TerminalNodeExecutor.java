package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for terminal nodes (END, START).
 *
 * <p>Simply marks completion without performing any action.
 */
@Component
public class TerminalNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(TerminalNodeExecutor.class);

  public TerminalNodeExecutor() {
    // No dependencies
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing terminal node: {} ({})", node.id(), node.type());

    if (node.type() == NodeType.END) {
      return NodeExecutionResult.success(node.id(), "Reached terminal state");
    } else if (node.type() == NodeType.START) {
      return NodeExecutionResult.success(node.id(), "Graph execution started");
    }

    return NodeExecutionResult.success(node.id(), "Terminal node completed");
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.END; // Primary type, but also handles START
  }
}
