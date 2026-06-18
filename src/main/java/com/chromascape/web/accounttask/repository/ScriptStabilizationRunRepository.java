package com.chromascape.web.accounttask.repository;

import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptStabilizationRunRepository
    extends JpaRepository<ScriptStabilizationRun, Long> {
  List<ScriptStabilizationRun> findByExecutedAtBetween(LocalDateTime start, LocalDateTime end);

  List<ScriptStabilizationRun> findByScriptKeyAndExecutedAtBetween(
      String scriptKey, LocalDateTime start, LocalDateTime end);

  List<ScriptStabilizationRun> findByReportIdOrderByExecutedAtAsc(String reportId);
}
