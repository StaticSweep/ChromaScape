package com.chromascape.web.debug.patchloop.entity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PatchLoopGraphNode} entities.
 *
 * <p>Provides CRUD operations and custom queries for graph node state management.
 */
@Repository
public interface PatchLoopGraphNodeRepository extends JpaRepository<PatchLoopGraphNode, Long> {

  /**
   * Finds all nodes for a specific graph execution.
   *
   * @param executionId the execution ID
   * @return list of nodes
   */
  List<PatchLoopGraphNode> findByExecutionIdOrderByStartedAtAsc(String executionId);

  /**
   * Finds all nodes for a specific execution and attempt.
   *
   * @param executionId the execution ID
   * @param attemptNumber the attempt number
   * @return list of nodes
   */
  List<PatchLoopGraphNode> findByExecutionIdAndAttemptNumberOrderByStartedAtAsc(
      String executionId, Integer attemptNumber);

  /**
   * Finds a specific node by execution ID and node ID.
   *
   * @param executionId the execution ID
   * @param nodeId the node ID
   * @return the node if found
   */
  default PatchLoopGraphNode findByExecutionIdAndNodeId(String executionId, String nodeId) {
    return findByExecutionIdOrderByStartedAtAsc(executionId).stream()
        .filter(n -> nodeId.equals(n.getNodeId()))
        .findFirst()
        .orElse(null);
  }

  /**
   * Finds nodes by execution ID and status.
   *
   * @param executionId the execution ID
   * @param status the node status
   * @return list of nodes
   */
  List<PatchLoopGraphNode> findByExecutionIdAndStatusOrderByStartedAtAsc(
      String executionId, NodeExecutionStatus status);

  /**
   * Finds all running nodes for a specific execution.
   *
   * @param executionId the execution ID
   * @return list of currently running nodes
   */
  default List<PatchLoopGraphNode> findRunningNodes(String executionId) {
    return findByExecutionIdAndStatusOrderByStartedAtAsc(executionId, NodeExecutionStatus.RUNNING);
  }

  /**
   * Finds all failed nodes for a specific execution.
   *
   * @param executionId the execution ID
   * @return list of failed nodes
   */
  default List<PatchLoopGraphNode> findFailedNodes(String executionId) {
    return findByExecutionIdAndStatusOrderByStartedAtAsc(executionId, NodeExecutionStatus.FAILED);
  }
}
