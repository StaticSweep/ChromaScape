package com.chromascape.web.debug.patchloop.entity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PatchLoopAnalysisArtifact} entities.
 *
 * <p>Provides CRUD operations and custom queries for analysis artifacts.
 */
@Repository
public interface PatchLoopAnalysisArtifactRepository
    extends JpaRepository<PatchLoopAnalysisArtifact, Long> {

  /**
   * Finds analysis artifacts by execution ID, ordered by timestamp.
   *
   * @param executionId the execution ID
   * @return list of analysis artifacts
   */
  List<PatchLoopAnalysisArtifact> findByExecutionIdOrderByTimestampAsc(String executionId);

  /**
   * Finds analysis artifacts by execution ID and attempt number.
   *
   * @param executionId the execution ID
   * @param attemptNumber the attempt number
   * @return list of analysis artifacts
   */
  List<PatchLoopAnalysisArtifact> findByExecutionIdAndAttemptNumber(
      String executionId, Integer attemptNumber);

  /**
   * Finds an analysis artifact by analysis ID.
   *
   * @param analysisId the analysis ID
   * @return the analysis artifact if found
   */
  PatchLoopAnalysisArtifact findByAnalysisId(String analysisId);
}
