package com.chromascape.web.debug.patchloop.entity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PatchLoopGraphExecution} entities.
 *
 * <p>Provides CRUD operations and custom queries for graph execution management.
 */
@Repository
public interface PatchLoopGraphExecutionRepository extends JpaRepository<PatchLoopGraphExecution, Long> {

  /**
   * Finds a graph execution by its unique execution ID.
   *
   * @param executionId the execution ID
   * @return the graph execution if found
   */
  Optional<PatchLoopGraphExecution> findByExecutionId(String executionId);

  /**
   * Finds all graph executions for a specific iteration.
   *
   * @param iterationId the iteration ID
   * @return list of graph executions
   */
  List<PatchLoopGraphExecution> findByIterationIdOrderByCreatedAtDesc(String iterationId);

  /**
   * Finds all active (non-terminal) graph executions.
   *
   * @return list of active graph executions
   */
  @Query(
      "SELECT e FROM PatchLoopGraphExecution e WHERE e.executionStatus IN ('CREATED', 'RUNNING', 'PAUSED') ORDER BY e.createdAt DESC")
  List<PatchLoopGraphExecution> findActiveExecutions();

  /**
   * Finds graph executions by status.
   *
   * @param status the status to filter by
   * @return list of graph executions
   */
  List<PatchLoopGraphExecution> findByExecutionStatusOrderByCreatedAtDesc(
      GraphExecutionStatus status);
}
