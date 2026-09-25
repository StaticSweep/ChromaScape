package com.chromascape.web.debug.patchloop.entity;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PatchLoopIteration} entities.
 *
 * <p>Provides CRUD operations and custom queries for iteration management.
 */
@Repository
public interface PatchLoopIterationRepository extends JpaRepository<PatchLoopIteration, Long> {

  /**
   * Finds an iteration by its unique iteration ID.
   *
   * @param iterationId the iteration ID
   * @return the iteration if found
   */
  Optional<PatchLoopIteration> findByIterationId(String iterationId);

  /**
   * Finds all iterations with a specific status, ordered by creation time (newest first).
   *
   * @param status the status to filter by
   * @return list of iterations
   */
  List<PatchLoopIteration> findByStatusOrderByCreatedAtDesc(PatchLoopStatus status);

  /**
   * Finds all iterations for a specific script, ordered by creation time (newest first).
   *
   * @param scriptName the script name
   * @return list of iterations
   */
  List<PatchLoopIteration> findByScriptNameOrderByCreatedAtDesc(String scriptName);

  /**
   * Finds all iterations, ordered by creation time (newest first).
   *
   * @return list of iterations
   */
  List<PatchLoopIteration> findAllByOrderByCreatedAtDesc();

  /**
   * Finds all active (non-terminal) iterations.
   *
   * @return list of active iterations
   */
  @Query(
      "SELECT i FROM PatchLoopIteration i WHERE i.status NOT IN"
          + " ('COMPLETED', 'FAILED', 'INSUFFICIENT_EVIDENCE') ORDER BY i.createdAt DESC")
  List<PatchLoopIteration> findActiveIterations();

  /**
   * Finds iterations by script name and status.
   *
   * @param scriptName the script name
   * @param status the status
   * @return list of matching iterations
   */
  List<PatchLoopIteration> findByScriptNameAndStatusOrderByCreatedAtDesc(
      String scriptName, PatchLoopStatus status);

  List<PatchLoopIteration> findBySourceTypeOrderByCreatedAtDesc(String sourceType);

  List<PatchLoopIteration> findByParentIterationIdOrderByCreatedAtDesc(String parentIterationId);

  Optional<PatchLoopIteration> findFirstBySourceTypeAndRunRecordingIdOrderByCreatedAtDesc(
      String sourceType, String runRecordingId);

  Optional<PatchLoopIteration> findFirstBySourceTypeAndRunSessionIdOrderByCreatedAtDesc(
      String sourceType, String runSessionId);

  /**
   * Finds an iteration by ID with a pessimistic write lock, guaranteeing no concurrent mutation of
   * the row during the transaction. Used by {@code BuilderGoalRunStateMutationService}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT i FROM PatchLoopIteration i WHERE i.iterationId = :iterationId")
  Optional<PatchLoopIteration> findByIterationIdForUpdate(@Param("iterationId") String iterationId);
}
