package com.chromascape.web.debug.patchloop.service.nodes;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import com.chromascape.web.debug.patchloop.graph.GraphNode;
import com.chromascape.web.debug.patchloop.graph.NodeExecutionResult;
import com.chromascape.web.debug.patchloop.service.GraphNodeExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executor for START nodes.
 *
 * <p>Initializes the graph execution.
 */
@Component
public class StartNodeExecutor implements GraphNodeExecutor {

  private static final Logger logger = LoggerFactory.getLogger(StartNodeExecutor.class);

  public StartNodeExecutor() {
    // No dependencies
  }

  @Override
  public NodeExecutionResult execute(GraphNode node, ExecutionContext context) throws Exception {
    logger.info("Executing START node: {}", node.id());

    // Initialize runtime data
    context.context().setRuntimeValue("startTime", java.time.LocalDateTime.now());

    return NodeExecutionResult.success(node.id(), "Graph execution initialized");
  }

  @Override
  public NodeType getNodeType() {
    return NodeType.START;
  }
}
