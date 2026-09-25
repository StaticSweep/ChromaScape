package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.patchloop.entity.NodeType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Registry for graph node executors.
 *
 * <p>Maps node types to their executor implementations.
 */
@Component
public class GraphNodeExecutorRegistry {

  private final Map<NodeType, GraphNodeExecutor> executors;

  public GraphNodeExecutorRegistry(List<GraphNodeExecutor> executorList) {
    this.executors =
        executorList.stream()
            .collect(Collectors.toMap(GraphNodeExecutor::getNodeType, Function.identity()));
  }

  /**
   * Gets the executor for a node type.
   *
   * @param nodeType the node type
   * @return the executor, or null if not found
   */
  public GraphNodeExecutor getExecutor(NodeType nodeType) {
    return executors.get(nodeType);
  }

  /**
   * Returns true if an executor is registered for the node type.
   *
   * @param nodeType the node type
   * @return true if an executor exists
   */
  public boolean hasExecutor(NodeType nodeType) {
    return executors.containsKey(nodeType);
  }

  /**
   * Gets all registered node types.
   *
   * @return set of registered node types
   */
  public java.util.Set<NodeType> getRegisteredTypes() {
    return executors.keySet();
  }
}
