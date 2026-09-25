package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.PatchCandidateRun;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PatchCandidateRunRepository extends JpaRepository<PatchCandidateRun, Long> {
  Optional<PatchCandidateRun> findByProposalId(String proposalId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PatchCandidateRun> findWithLockByProposalId(String proposalId);

  List<PatchCandidateRun> findByStateIn(Collection<String> states);
}
