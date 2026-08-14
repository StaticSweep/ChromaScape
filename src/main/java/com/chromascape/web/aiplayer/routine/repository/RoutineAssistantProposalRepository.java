package com.chromascape.web.aiplayer.routine.repository;
import com.chromascape.web.aiplayer.routine.entity.RoutineAssistantProposal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RoutineAssistantProposalRepository extends JpaRepository<RoutineAssistantProposal,Long>{Optional<RoutineAssistantProposal> findByProposalId(String proposalId);}
