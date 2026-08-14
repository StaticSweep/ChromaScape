package com.chromascape.web.accounttask.repository;

import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScriptStabilizationRunRepository
    extends JpaRepository<ScriptStabilizationRun, Long> {
  List<ScriptStabilizationRun> findByExecutedAtBetween(LocalDateTime start, LocalDateTime end);

  List<ScriptStabilizationRun> findByScriptKeyAndExecutedAtBetween(
      String scriptKey, LocalDateTime start, LocalDateTime end);

  Optional<ScriptStabilizationRun> findFirstByRunIdOrderByExecutedAtDesc(String runId);

  Optional<ScriptStabilizationRun> findFirstByRunIdAndAttemptIdOrderByExecutedAtDesc(
      String runId, String attemptId);

  List<ScriptStabilizationRun> findByRunIdOrderByAttemptIdAsc(String runId);

  boolean existsBySchemaVersionAndRunIdAndAttemptIdAndManifestHash(
      String schemaVersion, String runId, String attemptId, String manifestHash);

  Optional<ScriptStabilizationRun> findFirstByManifestHashOrderByExecutedAtDesc(
      String manifestHash);
}
