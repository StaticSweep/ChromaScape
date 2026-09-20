package com.chromascape.web.replay.artifact.repository;

import com.chromascape.web.replay.api.dto.ArtifactKind;
import com.chromascape.web.replay.artifact.entity.ArtifactRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtifactRefRepository extends JpaRepository<ArtifactRef, UUID> {

  List<ArtifactRef> findByRunIdOrderByCreatedAtAsc(UUID runId);

  List<ArtifactRef> findByRunIdAndState(UUID runId, String state);

  Optional<ArtifactRef> findFirstByRunIdAndKindAndStateOrderByCreatedAtDesc(
      UUID runId, ArtifactKind kind, String state);

  Optional<ArtifactRef> findByRunIdAndKindAndChecksum(
      UUID runId, ArtifactKind kind, String checksum);
}
