package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantProposal;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoutineAssistantProposalRepository extends JpaRepository<RoutineAssistantProposal, Long> {
  Optional<RoutineAssistantProposal> findByProposalId(String proposalId);

  Optional<RoutineAssistantProposal> findFirstByConversationIdOrderByIdDesc(String conversationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from RoutineAssistantProposal p where p.proposalId = :proposalId")
  Optional<RoutineAssistantProposal> findWithLockByProposalId(@Param("proposalId") String proposalId);
}
