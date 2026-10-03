package com.chromascape.web.accounttask.repository;

import com.chromascape.web.accounttask.entity.StabilizationArtifact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StabilizationArtifactRepository
    extends JpaRepository<StabilizationArtifact, Long> {

  Optional<StabilizationArtifact> findByArtifactId(String artifactId);

  /**
   * Resolves a stored artifact UUID by its durable external evidence reference within a run
   * (SC-572): evidence-reference → stored artifact UUID without a checksum lookup. The lookup is
   * ALWAYS run-scoped; {@code (run_id, external_reference)} is unique, so this returns at most one
   * row. A global reference lookup is intentionally absent.
   */
  Optional<StabilizationArtifact> findByRunIdAndExternalReference(
      String runId, String externalReference);

  List<StabilizationArtifact> findByRunIdOrderByCreatedAtAsc(String runId);

  List<StabilizationArtifact> findByRunIdAndAttemptIdOrderByCreatedAtAsc(
      String runId, String attemptId);

  boolean existsByRunId(String runId);
}
