package com.chromascape.web.debug.tickdebugger.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for accessing {@link DebugSession} entities. */
@Repository
public interface DebugSessionRepository extends JpaRepository<DebugSession, Long> {

  /** Find a debug session by its unique session ID. */
  Optional<DebugSession> findBySessionId(String sessionId);

  /** Find all active sessions (no end time set). */
  List<DebugSession> findByEndTimeIsNullOrderByStartTimeDesc();

  /** Find all sessions for a specific account, ordered by start time (newest first). */
  List<DebugSession> findByAccountNameOrderByStartTimeDesc(String accountName);

  /** Find all sessions, ordered by start time (newest first). */
  List<DebugSession> findAllByOrderByStartTimeDesc();

  /** Find sessions linked to a specific recording ID. */
  Optional<DebugSession> findByRecordingId(String recordingId);

  /** Find sessions that started within a time range. */
  List<DebugSession> findByStartTimeBetweenOrderByStartTimeDesc(
      LocalDateTime start, LocalDateTime end);

  /** Find sessions that ended before a specific time, ordered by end time (newest first). */
  List<DebugSession> findByEndTimeBeforeOrderByEndTimeDesc(LocalDateTime cutoff);

  /** Delete sessions older than the specified timestamp (for retention cleanup). */
  void deleteByEndTimeBefore(LocalDateTime cutoff);
}
