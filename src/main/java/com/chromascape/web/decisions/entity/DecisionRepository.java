package com.chromascape.web.decisions.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for DecisionEntity. */
@Repository
public interface DecisionRepository extends JpaRepository<DecisionEntity, Long> {

  /** Find decisions by recording session ID ordered by timestamp. */
  List<DecisionEntity> findByRecordingSessionIdOrderByTimestampAsc(Long recordingSessionId);

  /** Find decisions by script name ordered by timestamp descending. */
  List<DecisionEntity> findByScriptNameOrderByTimestampDesc(String scriptName);

  /** Find failed decisions ordered by timestamp descending. */
  List<DecisionEntity> findBySuccessFalseOrderByTimestampDesc();

  /** Find failed decisions by recording session. */
  List<DecisionEntity> findByRecordingSessionIdAndSuccessFalseOrderByTimestampAsc(
      Long recordingSessionId);

  /** Find decisions within timestamp range. */
  List<DecisionEntity> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

  /** Find decisions by recording session and timestamp range. */
  List<DecisionEntity> findByRecordingSessionIdAndTimestampBetweenOrderByTimestampAsc(
      Long recordingSessionId, LocalDateTime start, LocalDateTime end);

  /**
   * Find the N most recent decisions before a given timestamp for a session. Used for decision
   * history in AI prompts.
   */
  @Query(
      "SELECT d FROM DecisionEntity d WHERE d.recordingSessionId = :sessionId AND d.timestamp < :timestamp ORDER BY d.timestamp DESC")
  List<DecisionEntity> findRecentDecisionsBeforeTimestamp(Long sessionId, LocalDateTime timestamp);

  /** Find unique script names. */
  @Query("SELECT DISTINCT d.scriptName FROM DecisionEntity d ORDER BY d.scriptName")
  List<String> findDistinctScriptNames();

  /** Count decisions by recording session. */
  long countByRecordingSessionId(Long recordingSessionId);

  /** Count failed decisions by recording session. */
  long countByRecordingSessionIdAndSuccessFalse(Long recordingSessionId);

  /** Find all decisions ordered by timestamp descending. */
  List<DecisionEntity> findAllByOrderByTimestampDesc();

  /** Find a single decision by ID. */
  Optional<DecisionEntity> findById(Long id);
}
