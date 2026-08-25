package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.api.dto.MismatchLevel;
import com.chromascape.web.replay.run.entity.ReplayMismatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ReplayMismatchRepository extends JpaRepository<ReplayMismatch, UUID> {

    List<ReplayMismatch> findByRunIdOrderByFirstTickAscTickSequenceAsc(UUID runId);

    List<ReplayMismatch> findByRunIdAndLevelOrderByFirstTickAscTickSequenceAsc(UUID runId, MismatchLevel level);

    long countByRunIdAndLevel(UUID runId, MismatchLevel level);
}