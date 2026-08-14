package com.chromascape.web.debug.patchloop.entity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PatchLoopAttempt} entities.
 *
 * <p>Provides CRUD operations and custom queries for attempt management.
 */
@Repository
public interface PatchLoopAttemptRepository extends JpaRepository<PatchLoopAttempt, Long> {

  /**
   * Finds all attempts for a specific graph execution.
   *
   * @param executionId the execution ID
   * @return list of attempts ordered by attempt number
   */
  List<PatchLoopAttempt> findByExecutionIdOrderByAttemptNumberAsc(String executionId);

  /**
   * Finds an attempt by execution ID and attempt number.
   *
   * @param executionId the execution ID
   * @param attemptNumber the attempt number
   * @return the attempt if found
   */
  default PatchLoopAttempt findByExecutionIdAndAttemptNumber(
      String executionId, Integer attemptNumber) {
    return findByExecutionIdOrderByAttemptNumberAsc(executionId).stream()
        .filter(a -> attemptNumber.equals(a.getAttemptNumber()))
        .findFirst()
        .orElse(null);
  }
}
