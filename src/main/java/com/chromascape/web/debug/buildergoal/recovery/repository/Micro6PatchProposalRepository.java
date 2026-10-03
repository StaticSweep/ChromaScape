package com.chromascape.web.debug.buildergoal.recovery.repository;

import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchProposal;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface Micro6PatchProposalRepository extends JpaRepository<Micro6PatchProposal, String> {

  Optional<Micro6PatchProposal> findByProposalId(String proposalId);

  Optional<Micro6PatchProposal> findByProposalIdAndIncidentId(String proposalId, String incidentId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Micro6PatchProposal> findWithLockByProposalId(String proposalId);

  List<Micro6PatchProposal> findByIncidentIdOrderByVersionDesc(String incidentId);

  boolean existsByProposalHash(String proposalHash);
}
