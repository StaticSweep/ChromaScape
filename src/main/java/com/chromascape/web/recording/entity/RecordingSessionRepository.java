package com.chromascape.web.recording.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for RecordingSession. */
@Repository
public interface RecordingSessionRepository extends JpaRepository<RecordingSession, Long> {

  /** Find recording session by recording ID. */
  Optional<RecordingSession> findByRecordingId(String recordingId);

  /** Find all recording sessions by account name. */
  List<RecordingSession> findByAccountName(String accountName);

  /** Find all recording sessions by script name. */
  List<RecordingSession> findByScriptName(String scriptName);

  /** Find all recording sessions by trigger type. */
  List<RecordingSession> findByTriggerType(RecordingSession.SessionTrigger triggerType);

  /** Find recording sessions within a time range. */
  List<RecordingSession> findByStartTimeBetween(LocalDateTime start, LocalDateTime end);

  /** Find all recording sessions ordered by start time descending. */
  List<RecordingSession> findAllByOrderByStartTimeDesc();

  /** Find active recordings (endTime is null). */
  @Query("SELECT r FROM RecordingSession r WHERE r.endTime IS NULL")
  List<RecordingSession> findActiveRecordings();

  /** Find completed recordings by account name. */
  @Query(
      "SELECT r FROM RecordingSession r WHERE r.accountName = :accountName AND r.endTime IS NOT NULL ORDER BY r.startTime DESC")
  List<RecordingSession> findCompletedRecordingsByAccount(@Param("accountName") String accountName);

  /** Find recordings with multiple filters. */
  @Query(
      "SELECT r FROM RecordingSession r WHERE "
          + "(:accountName IS NULL OR r.accountName = :accountName) AND "
          + "(:scriptName IS NULL OR r.scriptName = :scriptName) AND "
          + "(:triggerType IS NULL OR r.triggerType = :triggerType) AND "
          + "(:start IS NULL OR r.startTime >= :start) AND "
          + "(:end IS NULL OR r.startTime <= :end)")
  List<RecordingSession> findSessionsWithFilters(
      @Param("accountName") String accountName,
      @Param("scriptName") String scriptName,
      @Param("triggerType") RecordingSession.SessionTrigger triggerType,
      @Param("start") LocalDateTime start,
      @Param("end") LocalDateTime end);
}
