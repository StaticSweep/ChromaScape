package com.chromascape.web.replay.run.repository;

import com.chromascape.web.replay.run.entity.ReplayCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReplayCheckpointRepository extends JpaRepository<ReplayCheckpoint, UUID> {

    List<ReplayCheckpoint> findByRunIdAndBranchKeyOrderByTickAsc(UUID runId, String branchKey);

    Optional<ReplayCheckpoint> findByRunIdAndBranchKeyAndTick(UUID runId, String branchKey, long tick);
}