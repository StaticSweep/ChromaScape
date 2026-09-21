package com.chromascape.web.debug.buildergoal.recovery.repository;

import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryArtifact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuilderGoalRecoveryArtifactRepository
    extends JpaRepository<BuilderGoalRecoveryArtifact, String> {

  Optional<BuilderGoalRecoveryArtifact> findByArtifactId(String artifactId);

  Optional<BuilderGoalRecoveryArtifact> findByArtifactIdAndIncidentId(
      String artifactId, String incidentId);

  List<BuilderGoalRecoveryArtifact> findByIncidentIdOrderByCreatedAtAsc(String incidentId);

  List<BuilderGoalRecoveryArtifact> findByIncidentIdAndKindOrderByCreatedAtAsc(
      String incidentId, String kind);

  Optional<BuilderGoalRecoveryArtifact> findByIncidentIdAndKindAndSha256AndSanitizationVersion(
      String incidentId, String kind, String sha256, String sanitizationVersion);
}
