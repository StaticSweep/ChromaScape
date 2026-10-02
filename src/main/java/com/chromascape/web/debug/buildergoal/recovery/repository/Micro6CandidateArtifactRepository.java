package com.chromascape.web.debug.buildergoal.recovery.repository;

import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6CandidateArtifact;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Micro6CandidateArtifactRepository
    extends JpaRepository<Micro6CandidateArtifact, String> {

  Optional<Micro6CandidateArtifact> findByCandidateId(String candidateId);

  Optional<Micro6CandidateArtifact> findFirstByIncidentIdOrderByCreatedAtDesc(String incidentId);

  Optional<Micro6CandidateArtifact> findByCandidateIdAndIncidentId(
      String candidateId, String incidentId);

  Optional<Micro6CandidateArtifact> findByCandidateDigest(String candidateDigest);

  Optional<Micro6CandidateArtifact> findByJobId(String jobId);
}
