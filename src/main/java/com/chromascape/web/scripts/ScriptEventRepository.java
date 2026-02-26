package com.chromascape.web.scripts;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for ScriptEventEntity. */
@Repository
public interface ScriptEventRepository extends JpaRepository<ScriptEventEntity, Long> {

  /** Find events by script name ordered by timestamp descending. */
  List<ScriptEventEntity> findByScriptNameOrderByTimestampDesc(String scriptName);

  /** Find all script events ordered by timestamp descending. */
  List<ScriptEventEntity> findAllByOrderByTimestampDesc();

  /** Find events within timestamp range. */
  List<ScriptEventEntity> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

  /** Find unique script names. */
  @Query("SELECT DISTINCT e.scriptName FROM ScriptEventEntity e ORDER BY e.scriptName")
  List<String> findDistinctScriptNames();
}
