package com.chromascape.web.aiplayer.routine.repository;
import com.chromascape.web.aiplayer.routine.entity.PatchCandidateRun;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PatchCandidateRunRepository extends JpaRepository<PatchCandidateRun,Long>{Optional<PatchCandidateRun> findByProposalId(String proposalId);}
