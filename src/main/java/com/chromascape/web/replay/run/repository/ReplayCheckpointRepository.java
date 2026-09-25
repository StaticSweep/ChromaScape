package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayCheckpointRepository extends JpaRepository<ReplayCheckpoint, UUID> {

  List<ReplayCheckpoint> findByRunIdAndBranchKeyOrderByTickAsc(UUID runId, String branchKey);

  Optional<ReplayCheckpoint> findByRunIdAndBranchKeyAndTick(
      UUID runId, String branchKey, long tick);

  /** Committed checkpoint count across all branches (report evidence uses this aggregate). */
  long countByRunId(UUID runId);
}
