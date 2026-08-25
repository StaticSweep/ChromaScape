package com.chromascape.web.replay.artifact.repository;

import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ArtifactRefRepository extends JpaRepository<ArtifactRef, UUID> {

    List<ArtifactRef> findByRunIdOrderByCreatedAtAsc(UUID runId);

    List<ArtifactRef> findByRunIdAndState(UUID runId, String state);
}