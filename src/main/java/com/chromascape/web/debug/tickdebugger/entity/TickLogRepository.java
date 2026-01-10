package com.chromascape.web.debug.tickdebugger.entity;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for accessing {@link TickLog} entities. */
@Repository
public interface TickLogRepository extends JpaRepository<TickLog, Long> {

  /** Find all logs for a session, ordered by tick. */
  List<TickLog> findBySessionIdOrderByTickAsc(String sessionId);

  /** Find logs for a specific tick. */
  List<TickLog> findBySessionIdAndTick(String sessionId, Long tick);

  /** Find logs for a session within a tick range. */
  List<TickLog> findBySessionIdAndTickBetweenOrderByTickAsc(
      String sessionId, Long startTick, Long endTick);

  /** Find error logs for a session. */
  List<TickLog> findBySessionIdAndSeverityIgnoreCaseOrderByTickAsc(
      String sessionId, String severity);

  /** Find logs by subsystem for a session. */
  List<TickLog> findBySessionIdAndSubsystemIgnoreCaseOrderByTickAsc(
      String sessionId, String subsystem);

  /** Search logs by message content. */
  List<TickLog> findBySessionIdAndMessageContainingIgnoreCaseOrderByTickAsc(
      String sessionId, String searchTerm);

  /** Find logs within a time range for a session. */
  List<TickLog> findBySessionIdAndTimestampBetweenOrderByTickAsc(
      String sessionId, LocalDateTime start, LocalDateTime end);

  /** Paginated log search with filters. */
  @Query(
      "SELECT l FROM TickLog l WHERE l.sessionId = :sessionId "
          + "AND (:subsystem IS NULL OR LOWER(l.subsystem) = LOWER(:subsystem)) "
          + "AND (:severity IS NULL OR LOWER(l.severity) = LOWER(:severity)) "
          + "AND (:search IS NULL OR LOWER(l.message) LIKE LOWER(CONCAT('%', :search, '%'))) "
          + "ORDER BY l.tick ASC")
  Page<TickLog> searchLogs(
      @Param("sessionId") String sessionId,
      @Param("subsystem") String subsystem,
      @Param("severity") String severity,
      @Param("search") String search,
      Pageable pageable);

  /** Find all error logs for a session (for timeline marker generation). */
  List<TickLog> findBySessionIdAndSeverityIgnoreCaseAndTickBetweenOrderByTickAsc(
      String sessionId, String severity, Long startTick, Long endTick);

  /** Delete all logs for a specific session. */
  void deleteBySessionId(String sessionId);

  /** Count logs for a session. */
  long countBySessionId(String sessionId);

  /** Count logs by severity for a session. */
  long countBySessionIdAndSeverityIgnoreCase(String sessionId, String severity);

  @Query(
      "SELECT l FROM TickLog l WHERE l.sessionId = :sessionId AND l.tick >= :tick ORDER BY l.tick ASC")
  List<TickLog> findFromTick(@Param("sessionId") String sessionId, @Param("tick") Long tick);

  @Query("SELECT MAX(l.tick) FROM TickLog l WHERE l.sessionId = :sessionId")
  Long findMaxTickBySessionId(@Param("sessionId") String sessionId);
}
