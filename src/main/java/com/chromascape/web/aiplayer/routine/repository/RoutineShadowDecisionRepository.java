package com.chromascape.web.aiplayer.routine.repository;

import com.chromascape.web.aiplayer.routine.entity.RoutineShadowDecision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineShadowDecisionRepository
    extends JpaRepository<RoutineShadowDecision, Long> {
  List<RoutineShadowDecision> findByShadowRunIdOrderByDecisionOrdinal(String shadowRunId);

  Optional<RoutineShadowDecision>
      findByShadowRunIdAndSourceEventKindAndSourceBatchSequenceAndSourceEventIndex(
          String shadowRunId, String kind, int sequence, int index);
}
