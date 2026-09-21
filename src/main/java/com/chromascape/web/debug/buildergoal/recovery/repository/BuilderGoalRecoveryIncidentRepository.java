package com.chromascape.web.debug.buildergoal.recovery.repository;

import com.chromascape.web.debug.buildergoal.recovery.entity.BuilderGoalRecoveryIncident;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface BuilderGoalRecoveryIncidentRepository
    extends JpaRepository<BuilderGoalRecoveryIncident, String> {

  Optional<BuilderGoalRecoveryIncident> findByIterationIdAndEvidenceManifestHash(
      String iterationId, String evidenceManifestHash);

  List<BuilderGoalRecoveryIncident> findByIterationIdOrderByCreatedAtDesc(String iterationId);

  Optional<BuilderGoalRecoveryIncident> findFirstByIterationIdOrderByCreatedAtDesc(
      String iterationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<BuilderGoalRecoveryIncident> findWithLockByIncidentId(String incidentId);

  List<BuilderGoalRecoveryIncident> findByState(String state);
}
