package com.chromascape.web.debug.patchloop.entity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PatchLoopPatchArtifact} entities.
 *
 * <p>Provides CRUD operations and custom queries for patch artifact management.
 */
@Repository
public interface PatchLoopPatchArtifactRepository
    extends JpaRepository<PatchLoopPatchArtifact, Long> {

  /**
   * Finds a patch artifact by its unique patch ID.
   *
   * @param patchId the patch ID
   * @return the patch artifact if found
   */
  Optional<PatchLoopPatchArtifact> findByPatchId(String patchId);

  /**
   * Finds all patches for a specific graph execution.
   *
   * @param executionId the execution ID
   * @return list of patches ordered by creation time
   */
  List<PatchLoopPatchArtifact> findByExecutionIdOrderByCreatedAtAsc(String executionId);

  /**
   * Finds all patches with a specific apply status.
   *
   * @param status the apply status
   * @return list of patches
   */
  List<PatchLoopPatchArtifact> findByApplyStatusOrderByCreatedAtDesc(PatchApplyStatus status);

  /**
   * Finds all applied patches for a specific execution.
   *
   * @param executionId the execution ID
   * @return list of applied patches
   */
  List<PatchLoopPatchArtifact> findByExecutionIdAndApplyStatusOrderByCreatedAtAsc(
      String executionId, PatchApplyStatus status);
}
