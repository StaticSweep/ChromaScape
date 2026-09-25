package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.run.entity.StateHashSample;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StateHashSampleRepository
    extends JpaRepository<StateHashSample, StateHashSample.StateHashSampleId> {

  List<StateHashSample> findByIdRunIdOrderByIdTickAscIdSequenceAsc(UUID runId);

  long countByIdRunId(UUID runId);

  /** Last ordered sample without loading the whole history (bounded recovery/report reads). */
  Optional<StateHashSample> findTopByIdRunIdOrderByIdTickDescIdSequenceDesc(UUID runId);
}
