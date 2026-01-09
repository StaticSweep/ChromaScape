package com.chromascape.web.logs;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for LogEntity. */
@Repository
public interface LogRepository extends JpaRepository<LogEntity, Long> {

  /** Find logs by script name. */
  List<LogEntity> findByScriptName(String scriptName);

  /** Find logs by timestamp range. */
  List<LogEntity> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

  /** Find logs by script name and timestamp range. */
  List<LogEntity> findByScriptNameAndTimestampBetween(
      String scriptName, LocalDateTime start, LocalDateTime end);

  /** Find logs by level. */
  List<LogEntity> findByLevel(String level);

  /** Find all logs ordered by timestamp descending. */
  List<LogEntity> findAllByOrderByTimestampDesc();

  /** Search logs by message content (case insensitive). */
  List<LogEntity> findByMessageContainingIgnoreCase(String message);

  /** Custom query to find logs with multiple filters. */
  @Query(
      "SELECT l FROM LogEntity l WHERE "
          + "(:scriptName IS NULL OR l.scriptName = :scriptName) AND "
          + "(:level IS NULL OR l.level = :level) AND "
          + "(:start IS NULL OR l.timestamp >= :start) AND "
          + "(:end IS NULL OR l.timestamp <= :end) AND "
          + "(:search IS NULL OR LOWER(l.message) LIKE LOWER(CONCAT('%', :search, '%')))")
  List<LogEntity> findLogsWithFilters(
      @Param("scriptName") String scriptName,
      @Param("level") String level,
      @Param("start") LocalDateTime start,
      @Param("end") LocalDateTime end,
      @Param("search") String search);
}
