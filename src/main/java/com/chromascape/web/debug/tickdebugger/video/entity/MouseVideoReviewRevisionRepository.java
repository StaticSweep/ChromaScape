package com.chromascape.web.debug.tickdebugger.video.entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Append-only review history for a source. */
@Repository
public interface MouseVideoReviewRevisionRepository
    extends JpaRepository<MouseVideoReviewRevision, UUID> {

  List<MouseVideoReviewRevision> findBySourceIdOrderByCreatedAtAsc(UUID sourceId);

  Optional<MouseVideoReviewRevision> findByIdAndSourceId(UUID id, UUID sourceId);

  Optional<MouseVideoReviewRevision> findFirstBySourceIdOrderByCreatedAtDesc(UUID sourceId);

  Optional<MouseVideoReviewRevision> findBySourceIdAndRequestId(UUID sourceId, String requestId);

  long countBySourceId(UUID sourceId);
}
