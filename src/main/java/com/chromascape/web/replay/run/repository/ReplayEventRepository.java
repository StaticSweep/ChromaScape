package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.run.entity.ReplayEvent;
import com.chromascape.web.replay.run.entity.ReplayEventId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ReplayEventRepository extends JpaRepository<ReplayEvent, ReplayEventId> {

    List<ReplayEvent> findByRunIdAndOrdinalGreaterThanOrderByOrdinalAsc(UUID runId, long afterOrdinal, Pageable pageable);

    List<ReplayEvent> findByRunIdOrderByOrdinalAsc(UUID runId);

    long countByRunId(UUID runId);
}
