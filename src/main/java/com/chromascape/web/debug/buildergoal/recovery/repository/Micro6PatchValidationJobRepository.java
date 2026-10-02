package com.chromascape.web.debug.buildergoal.recovery.repository;

import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchValidationJob;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface Micro6PatchValidationJobRepository
    extends JpaRepository<Micro6PatchValidationJob, String> {

  Optional<Micro6PatchValidationJob> findByJobId(String jobId);

  Optional<Micro6PatchValidationJob> findFirstByIncidentIdOrderByCreatedAtDesc(String incidentId);

  Optional<Micro6PatchValidationJob> findByJobIdAndProposalId(String jobId, String proposalId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Micro6PatchValidationJob> findWithLockByJobId(String jobId);

  Optional<Micro6PatchValidationJob> findByIncidentIdAndIdempotencyKey(
      String incidentId, String idempotencyKey);

  List<Micro6PatchValidationJob> findByStateIn(Collection<String> states);

  List<Micro6PatchValidationJob> findByProposalIdOrderByCreatedAtDesc(String proposalId);
}
