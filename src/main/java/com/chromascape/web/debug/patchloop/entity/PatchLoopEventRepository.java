package com.chromascape.web.debug.patchloop.entity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PatchLoopEvent} entities.
 *
 * <p>Provides CRUD operations and queries for event logs.
 */
@Repository
public interface PatchLoopEventRepository extends JpaRepository<PatchLoopEvent, Long> {

  /**
   * Finds all events for a specific iteration, ordered by timestamp (oldest first).
   *
   * @param iterationId the iteration ID
   * @return list of events in chronological order
   */
  List<PatchLoopEvent> findByIterationIdOrderByTimestampAsc(String iterationId);

  /**
   * Finds events by iteration ID and event type.
   *
   * @param iterationId the iteration ID
   * @param eventType the event type
   * @return list of matching events
   */
  List<PatchLoopEvent> findByIterationIdAndEventType(
      String iterationId, PatchLoopEventType eventType);

  /**
   * Finds events by iteration ID and severity level.
   *
   * @param iterationId the iteration ID
   * @param severity the severity level (INFO, WARN, ERROR)
   * @return list of matching events
   */
  List<PatchLoopEvent> findByIterationIdAndSeverity(String iterationId, String severity);
}
