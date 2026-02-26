package com.chromascape.web.debug.patchloop.entity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link ExecutionRuntimeSnapshot} entities.
 *
 * <p>Provides CRUD operations and custom queries for runtime snapshots.
 */
@Repository
public interface ExecutionRuntimeSnapshotRepository
    extends JpaRepository<ExecutionRuntimeSnapshot, Long> {

  /**
   * Finds a snapshot by execution ID.
   *
   * @param executionId the execution ID
   * @return the snapshot if found
   */
  Optional<ExecutionRuntimeSnapshot> findByExecutionId(String executionId);
}
