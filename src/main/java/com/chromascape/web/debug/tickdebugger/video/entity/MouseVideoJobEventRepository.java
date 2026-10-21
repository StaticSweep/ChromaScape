package com.chromascape.web.debug.tickdebugger.video.entity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Immutable job event history, ordered by insertion. */
@Repository
public interface MouseVideoJobEventRepository extends JpaRepository<MouseVideoJobEvent, Long> {

  List<MouseVideoJobEvent> findByJobIdOrderByIdAsc(UUID jobId);

  long countByJobId(UUID jobId);
}
