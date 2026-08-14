package com.chromascape.web.debug.patchloop.entity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PatchLoopRetestArtifact} entities.
 *
 * <p>Provides CRUD operations and custom queries for retest artifacts.
 */
@Repository
public interface PatchLoopRetestArtifactRepository
    extends JpaRepository<PatchLoopRetestArtifact, Long> {

  /**
   * Finds retest artifacts by execution ID, ordered by timestamp.
   *
   * @param executionId the execution ID
   * @return list of retest artifacts
   */
  List<PatchLoopRetestArtifact> findByExecutionIdOrderByTimestampAsc(String executionId);

  /**
   * Finds retest artifacts by execution ID and attempt number.
   *
   * @param executionId the execution ID
   * @param attemptNumber the attempt number
   * @return list of retest artifacts
   */
  List<PatchLoopRetestArtifact> findByExecutionIdAndAttemptNumber(
      String executionId, Integer attemptNumber);
}
