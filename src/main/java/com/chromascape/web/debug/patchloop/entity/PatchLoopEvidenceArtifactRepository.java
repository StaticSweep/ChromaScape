package com.chromascape.web.debug.patchloop.entity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PatchLoopEvidenceArtifact} entities.
 *
 * <p>Provides CRUD operations and custom queries for evidence artifacts.
 */
@Repository
public interface PatchLoopEvidenceArtifactRepository
    extends JpaRepository<PatchLoopEvidenceArtifact, Long> {

  /**
   * Finds evidence artifacts by execution ID, ordered by timestamp.
   *
   * @param executionId the execution ID
   * @return list of evidence artifacts
   */
  List<PatchLoopEvidenceArtifact> findByExecutionIdOrderByTimestampAsc(String executionId);

  /**
   * Finds evidence artifacts by execution ID and attempt number.
   *
   * @param executionId the execution ID
   * @param attemptNumber the attempt number
   * @return list of evidence artifacts
   */
  List<PatchLoopEvidenceArtifact> findByExecutionIdAndAttemptNumber(
      String executionId, Integer attemptNumber);

  /**
   * Finds evidence artifacts by execution ID and evidence type.
   *
   * @param executionId the execution ID
   * @param evidenceType the evidence type
   * @return list of evidence artifacts
   */
  List<PatchLoopEvidenceArtifact> findByExecutionIdAndEvidenceTypeOrderByTimestampAsc(
      String executionId, String evidenceType);
}
