package com.chromascape.web.debug.tickdebugger.entity;

import com.chromascape.web.debug.tickdebugger.dto.SessionCounts;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for accessing {@link DebugSession} entities. */
@Repository
public interface DebugSessionRepository extends JpaRepository<DebugSession, Long> {

  /** Find a debug session by its unique session ID. */
  Optional<DebugSession> findBySessionId(String sessionId);

  /** Serialize tick ingestion with session closure for the lifetime of the transaction. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM DebugSession s WHERE s.sessionId = :sessionId")
  Optional<DebugSession> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

  /** Repair/finalization by recording identity uses the same session row lock. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM DebugSession s WHERE s.recordingId = :recordingId")
  Optional<DebugSession> findByRecordingIdForUpdate(@Param("recordingId") String recordingId);

  /** Find all active sessions (no end time set). */
  List<DebugSession> findByEndTimeIsNullOrderByStartTimeDesc();

  /** Find active sessions that are not yet linked to a recording. */
  List<DebugSession> findByEndTimeIsNullAndRecordingIdIsNullOrderByStartTimeDesc();

  /** Find all sessions for a specific account, ordered by start time (newest first). */
  List<DebugSession> findByAccountNameOrderByStartTimeDesc(String accountName);

  /** Find all sessions, ordered by start time (newest first). */
  List<DebugSession> findAllByOrderByStartTimeDesc();

  /**
   * Find the most recent debug session for a specific script name.
   *
   * @param scriptName the script name
   * @return the most recent session if found
   */
  Optional<DebugSession> findFirstByScriptNameOrderByStartTimeDesc(String scriptName);

  /** Find sessions linked to a specific recording ID. */
  Optional<DebugSession> findByRecordingId(String recordingId);

  /** Find sessions that are linked to any recording ID, ordered by start time (newest first). */
  List<DebugSession> findByRecordingIdIsNotNullOrderByStartTimeDesc();

  /** Find sessions that started within a time range. */
  List<DebugSession> findByStartTimeBetweenOrderByStartTimeDesc(
      LocalDateTime start, LocalDateTime end);

  /** Find sessions that ended before a specific time, ordered by end time (newest first). */
  List<DebugSession> findByEndTimeBeforeOrderByEndTimeDesc(LocalDateTime cutoff);

  /** Delete sessions older than the specified timestamp (for retention cleanup). */
  void deleteByEndTimeBefore(LocalDateTime cutoff);

  /**
   * Fetch aggregate counts for multiple sessions in a single query.
   *
   * <p>Reduces N+1 query problem by fetching tick counts, event counts, and failure marker counts
   * for all requested sessions in one JOIN query.
   *
   * @param sessionIds list of session IDs to fetch counts for
   * @return list of SessionCounts (convert to map in service layer if needed)
   */
  @Query(
      """
      SELECT new com.chromascape.web.debug.tickdebugger.dto.SessionCounts(
        s.sessionId,
        COUNT(DISTINCT snap.id),
        COUNT(DISTINCT log.id),
        COUNT(DISTINCT CASE
          WHEN (UPPER(m.markerType) IN ('ERROR', 'DAMAGE', 'DEATH') OR UPPER(m.severity) = 'ERROR')
          THEN m.id
          ELSE NULL
        END)
      )
      FROM DebugSession s
      LEFT JOIN TickSnapshot snap ON snap.sessionId = s.sessionId
      LEFT JOIN TickLog log ON log.sessionId = s.sessionId
      LEFT JOIN TimelineMarker m ON m.sessionId = s.sessionId
      WHERE s.sessionId IN :sessionIds
      GROUP BY s.sessionId
      """)
  List<SessionCounts> findCountsBySessionIds(@Param("sessionIds") List<String> sessionIds);
}
