package com.chromascape.web.debug.patchloop.entity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PatchLoopValidationArtifact} entities.
 *
 * <p>Provides CRUD operations and custom queries for validation artifacts.
 */
@Repository
public interface PatchLoopValidationArtifactRepository
    extends JpaRepository<PatchLoopValidationArtifact, Long> {

  /**
   * Finds validation artifacts by execution ID, ordered by timestamp.
   *
   * @param executionId the execution ID
   * @return list of validation artifacts
   */
  List<PatchLoopValidationArtifact> findByExecutionIdOrderByTimestampAsc(String executionId);

  /**
   * Finds validation artifacts by execution ID and attempt number.
   *
   * @param executionId the execution ID
   * @param attemptNumber the attempt number
   * @return list of validation artifacts
   */
  List<PatchLoopValidationArtifact> findByExecutionIdAndAttemptNumber(
      String executionId, Integer attemptNumber);

  /**
   * Finds validation artifacts by patch ID.
   *
   * @param patchId the patch ID
   * @return list of validation artifacts
   */
  List<PatchLoopValidationArtifact> findByPatchIdOrderByTimestampAsc(String patchId);
}
