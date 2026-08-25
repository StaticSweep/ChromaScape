package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.run.entity.StateHashSample;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface StateHashSampleRepository extends JpaRepository<StateHashSample, StateHashSample.StateHashSampleId> {

    List<StateHashSample> findByIdRunIdOrderByTickAscAscSequenceAsc(UUID runId);

    long countByIdRunId(UUID runId);
}
