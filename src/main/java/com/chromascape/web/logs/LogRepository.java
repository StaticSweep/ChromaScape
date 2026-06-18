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
          + "(:source IS NULL OR l.source LIKE CONCAT(:source, '%')) AND "
          + "(:start IS NULL OR l.timestamp >= :start) AND "
          + "(:end IS NULL OR l.timestamp <= :end) AND "
          + "(:search IS NULL OR LOWER(l.message) LIKE LOWER(CONCAT('%', :search, '%')))")
  List<LogEntity> findLogsWithFilters(
      @Param("scriptName") String scriptName,
      @Param("level") String level,
      @Param("source") String source,
      @Param("start") LocalDateTime start,
      @Param("end") LocalDateTime end,
      @Param("search") String search);

  /** Find all distinct log sources. */
  @Query("SELECT DISTINCT l.source FROM LogEntity l WHERE l.source IS NOT NULL ORDER BY l.source")
  List<String> findDistinctSources();

  // ActionLogger-specific query methods

  /** Filter by log type (DIALOGUE, INVENTORY, VARBIT, ANIMATION) */
  List<LogEntity> findByLogType(String logType);

  /** Filter by tick range */
  List<LogEntity> findByTickBetween(Integer startTick, Integer endTick);

  /** Search in structured data (JSON contains) */
  @Query("SELECT l FROM LogEntity l WHERE l.structuredData LIKE %:pattern%")
  List<LogEntity> searchStructuredData(@Param("pattern") String pattern);

  /** Find logs by log type ordered by tick */
  List<LogEntity> findByLogTypeOrderByTickAsc(String logType);

  /** Find logs by tick and log type */
  List<LogEntity> findByTickAndLogType(Integer tick, String logType);

  /** Find logs by source and timestamp window for evidence collection. */
  List<LogEntity> findBySourceAndTimestampBetweenOrderByTimestampAsc(
      String source, LocalDateTime start, LocalDateTime end);

  /** Find all logs in a timestamp window ordered chronologically. */
  List<LogEntity> findByTimestampBetweenOrderByTimestampAsc(LocalDateTime start, LocalDateTime end);
}
