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

  List<StabilizationArtifact> findByRunIdOrderByCreatedAtAsc(String runId);

  List<StabilizationArtifact> findByRunIdAndAttemptIdOrderByCreatedAtAsc(String runId, String attemptId);

  boolean existsByRunId(String runId);
}
