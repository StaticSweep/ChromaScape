package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.BehaviorGraphPublication;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BehaviorGraphPublicationRepository
    extends JpaRepository<BehaviorGraphPublication, Long> {
  Optional<BehaviorGraphPublication> findTopByGraphIdOrderByGraphVersionDesc(String graphId);

  Optional<BehaviorGraphPublication> findBySourceDraftIdAndSourceDraftRevision(
      String draftId, long revision);
}
