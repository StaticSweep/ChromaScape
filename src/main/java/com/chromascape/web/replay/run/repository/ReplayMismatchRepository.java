package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.api.dto.MismatchLevel;
import com.chromascape.web.replay.run.entity.ReplayMismatch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayMismatchRepository extends JpaRepository<ReplayMismatch, UUID> {

  List<ReplayMismatch> findByRunIdOrderByFirstTickAscTickSequenceAsc(UUID runId);

  List<ReplayMismatch> findByRunIdAndLevelOrderByFirstTickAscTickSequenceAsc(
      UUID runId, MismatchLevel level);

  long countByRunIdAndLevel(UUID runId, MismatchLevel level);

  /** First hard mismatch in authoritative order for report-v1 firstHardMismatch evidence. */
  Optional<ReplayMismatch>
      findFirstByRunIdAndLevelOrderByFirstTickAscTickSequenceAscCreatedAtAscIdAsc(
          UUID runId, MismatchLevel level);
}
