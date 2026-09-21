package com.chromascape.web.debug.buildergoal.recovery.repository;

import com.chromascape.web.debug.buildergoal.recovery.entity.Micro6PatchApproval;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Micro6PatchApprovalRepository extends JpaRepository<Micro6PatchApproval, String> {

  Optional<Micro6PatchApproval> findByApprovalId(String approvalId);

  Optional<Micro6PatchApproval> findByApprovalIdAndProposalId(String approvalId, String proposalId);

  Optional<Micro6PatchApproval> findByProposalHashAndStatus(String proposalHash, String status);
}
